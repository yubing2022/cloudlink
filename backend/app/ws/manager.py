"""WebSocket connection manager.

Two kinds of connections:
- HA connections: keyed by cloud_token (HA instance identity)
- Client connections: keyed by user_id (mobile app user identity)
"""
import asyncio
import logging
from typing import Optional

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class ConnectionManager:
    def __init__(self) -> None:
        # cloud_token -> WebSocket (HA connections are 1:1 per instance)
        self._ha_connections: dict[str, WebSocket] = {}
        # user_id -> set of WebSocket (a user can have multiple devices)
        self._client_connections: dict[int, set[WebSocket]] = {}
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
