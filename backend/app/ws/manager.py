"""WebSocket connection manager.

Two kinds of connections:
- HA connections: keyed by cloud_token (HA instance identity)
- Client connections: keyed by user_id (mobile app user identity)
"""
import asyncio
import logging
from typing import Any, Optional

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class ConnectionManager:
    def __init__(self) -> None:
        # cloud_token -> WebSocket (HA connections are 1:1 per instance)
        self._ha_connections: dict[str, WebSocket] = {}
        # user_id -> set of WebSocket (a user can have multiple devices)
        self._client_connections: dict[int, set[WebSocket]] = {}
        # request_id -> Future[dict] for in-flight device_action calls
        # awaiting their post-action state from the HA plugin. Used by
        # /api/devices/{id}/action to return the *new* state instead of
        # the pre-action DB row.
        self._pending_action_results: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()
        self._heartbeat_task: Optional[asyncio.Task] = None

    async def start(self) -> None:
        """Start background heartbeat check."""
        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())
        logger.info("ConnectionManager started")

    async def stop(self) -> None:
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass
        logger.info("ConnectionManager stopped")

    # ---- HA connections ----

    async def connect_ha(self, cloud_token: str, ws: WebSocket) -> None:
        async with self._lock:
            old = self._ha_connections.get(cloud_token)
            if old and old != ws:
                # Another connection from the same HA instance - close the old one
                try:
                    await old.close(code=1000, reason="Replaced by new connection")
                except Exception:
                    pass
            self._ha_connections[cloud_token] = ws
            logger.info("HA connected: %s...", cloud_token[:8])

    async def disconnect_ha(self, cloud_token: str) -> None:
        async with self._lock:
            self._ha_connections.pop(cloud_token, None)
        # Wake any callers blocked in request_action_and_wait() so they
        # get a ConnectionError instead of timing out 5s later. Done
        # outside the lock because the waiters' set_exception() can
        # re-enter send_to_ha() under cancellation.
        n = self._drop_pending_actions_for_connection()
        if n:
            logger.info(
                "HA disconnected: %s... failed %d in-flight action request(s)",
                cloud_token[:8], n,
            )
        else:
            logger.info("HA disconnected: %s...", cloud_token[:8])

    async def send_to_ha(self, cloud_token: str, message: dict) -> bool:
        """Send JSON message to a specific HA instance. Returns success."""
        ws = self._ha_connections.get(cloud_token)
        if ws is None:
            return False
        try:
            await ws.send_json(message)
            return True
        except Exception as e:
            logger.warning("Failed to send to HA %s: %s", cloud_token[:8], e)
            await self.disconnect_ha(cloud_token)
            return False

    def is_ha_online(self, cloud_token: str) -> bool:
        return cloud_token in self._ha_connections

    # ---- Action request/response (HA plugin round-trip) ----

    def _register_pending_action(self, request_id: str) -> asyncio.Future:
        """Create a Future for a device_action's eventual result message.

        Caller MUST eventually resolve it via _resolve_pending_action()
        (or it will be GC'd on disconnect). The Future's result is the
        full `device_action_result` dict the HA plugin sends back.
        """
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self._pending_action_results[request_id] = fut
        return fut

    def _resolve_pending_action(self, request_id: str, result: dict) -> bool:
        """Resolve the Future for a completed device_action. Returns True
        if a waiter was actually found (False = late/duplicate result)."""
        fut = self._pending_action_results.pop(request_id, None)
        if fut is None or fut.done():
            return False
        fut.set_result(result)
        return True

    def _fail_pending_action(self, request_id: str, exc: BaseException) -> bool:
        fut = self._pending_action_results.pop(request_id, None)
        if fut is None or fut.done():
            return False
        fut.set_exception(exc)
        return False

    def _drop_pending_actions_for_connection(self) -> int:
        """Called when an HA WS disconnects. Fails all in-flight action
        requests so callers don't hang on their await."""
        if not self._pending_action_results:
            return 0
        ids = list(self._pending_action_results.keys())
        n = 0
        for rid in ids:
            if self._fail_pending_action(rid, ConnectionError("HA disconnected")):
                n += 1
        return n

    async def request_action_and_wait(
        self,
        cloud_token: str,
        action_msg: dict,
        *,
        timeout: float = 5.0,
    ) -> dict:
        """Send a device_action to HA and wait for its post-action result.

        The action_msg MUST already contain a `request_id` field; the
        same id is expected in the plugin's `device_action_result`
        reply. Raises:
          - ConnectionError: HA is offline or WS write failed
          - asyncio.TimeoutError: plugin didn't reply within `timeout` s
          - The result may also be `{"ok": False, "error": "..."}` if
            the plugin's service call failed (the plugin sends a
            result dict in that case, not an exception).
        """
        request_id = action_msg.get("request_id")
        if not request_id:
            raise ValueError("action_msg must carry a request_id")
        fut = self._register_pending_action(request_id)
        try:
            sent = await self.send_to_ha(cloud_token, action_msg)
            if not sent:
                raise ConnectionError("HA is not connected")
            return await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            # Don't leak the Future; drop it so a future late reply
            # for the same id doesn't accidentally resolve something
            # else's await.
            self._pending_action_results.pop(request_id, None)
            raise
        except BaseException:
            # Same cleanup on any other failure path.
            self._pending_action_results.pop(request_id, None)
            raise

    # ---- Client (mobile app) connections ----

    async def connect_client(self, user_id: int, ws: WebSocket) -> None:
        async with self._lock:
            self._client_connections.setdefault(user_id, set()).add(ws)
            logger.info("Client connected: user_id=%d (now %d devices)", user_id, len(self._client_connections[user_id]))

    async def disconnect_client(self, user_id: int, ws: WebSocket) -> None:
        async with self._lock:
            conns = self._client_connections.get(user_id)
            if conns:
                conns.discard(ws)
                if not conns:
                    self._client_connections.pop(user_id, None)
            logger.info("Client disconnected: user_id=%d", user_id)

    async def broadcast_to_user(self, user_id: int, message: dict) -> int:
        """Send JSON message to all of a user's devices. Returns number sent."""
        conns = list(self._client_connections.get(user_id, set()))
        if not conns:
            return 0
        sent = 0
        dead = []
        for ws in conns:
            try:
                await ws.send_json(message)
                sent += 1
            except Exception as e:
                logger.warning("Failed to send to client: %s", e)
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    self._client_connections.get(user_id, set()).discard(ws)
        return sent

    # ---- Heartbeat (mark HA instances as offline if no recent activity) ----
    # Note: actual last_seen updates happen via the /heartbeat HTTP endpoint.

    async def _heartbeat_loop(self) -> None:
        """Periodic check that sends ping frames to keep connections alive."""
        while True:
            await asyncio.sleep(30)
            # FastAPI/Starlette websockets auto-handle pings at protocol level
            # This loop is here as a hook for future per-connection health checks


# Global singleton
ws_manager = ConnectionManager()
