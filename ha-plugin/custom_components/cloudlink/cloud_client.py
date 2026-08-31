"""CloudLink HA Plugin - Cloud WebSocket client."""
import asyncio
import json
import logging

import websockets
from homeassistant.core import Event, HomeAssistant
from websockets.exceptions import ConnectionClosed

from .const import (
    DOMAIN,
    HEARTBEAT_INTERVAL_SECONDS,
    INITIAL_BACKOFF_SECONDS,
    MAX_BACKOFF_SECONDS,
)

_LOGGER = logging.getLogger(__name__)
# Force WARNING level so HA logs show our messages
_LOGGER.setLevel(logging.WARNING)


class CloudClient:
    """Manages WebSocket connection to CloudLink cloud."""

    def __init__(self, hass: HomeAssistant, cloud_url: str, cloud_token: str):
        self.hass = hass
        self.cloud_url = cloud_url.rstrip("/")
        self.cloud_token = cloud_token
        self.ws = None
        self._stopped = False
        self._backoff = INITIAL_BACKOFF_SECONDS
        self._unsub_state = None
        self._unsub_started = None

    async def start(self) -> None:
        """Start the connection loop."""
        _LOGGER.warning("CloudLink: start() called, url=%s", self.cloud_url)
        while not self._stopped:
            try:
                await self._connect_and_serve()
            except Exception as e:
                _LOGGER.warning("CloudLink: connect error: %s", e)
            
            if self._stopped:
                break
            
            _LOGGER.warning(
                "CloudLink: reconnecting in %ds", self._backoff,
            )
            await asyncio.sleep(self._backoff)
            self._backoff = min(self._backoff * 2, MAX_BACKOFF_SECONDS)

    async def stop(self) -> None:
        """Stop the connection."""
        _LOGGER.warning("CloudLink: stop() called")
        self._stopped = True
        for unsub in (self._unsub_state, self._unsub_started):
            if unsub:
                try:
                    unsub()
                except Exception:
                    pass
                unsub = None
        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass

    async def _connect_and_serve(self) -> None:
        """Connect to cloud and serve the WebSocket."""
        ws_url = (
            self.cloud_url
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        ) + f"/api/ws/ha?token={self.cloud_token}"
        
        _LOGGER.warning("CloudLink: connecting to %s", ws_url.split("?")[0])
        
        async with websockets.connect(ws_url, ping_interval=20, ping_timeout=10) as ws:
            self.ws = ws
            self._backoff = INITIAL_BACKOFF_SECONDS
            
            _LOGGER.warning("CloudLink: WS connected, sending initial device_sync")
            
            # Try immediate sync (entities may or may not be ready)
            await self._send_full_sync()
            
            # ALSO listen for homeassistant_started to sync again
            # (handles the case where HA wasn't ready at connect time)
            async def _on_started(event):
                _LOGGER.warning("CloudLink: homeassistant_started fired, re-syncing")
                await self._send_full_sync()
            self._unsub_started = self.hass.bus.async_listen_once(
                "homeassistant_started", _on_started,
            )
            
            # Subscribe to state changes
            self._unsub_state = self.hass.bus.async_listen(
                "state_changed", self._on_state_changed
            )
            
            _LOGGER.warning("CloudLink: subscribed to state_changed, entering message loop")
            
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    await self._handle_message(msg)
                except json.JSONDecodeError:
                    _LOGGER.warning("CloudLink: invalid JSON: %r", raw[:200])
                except Exception:
                    _LOGGER.exception("CloudLink: error handling message")

    async def _send_full_sync(self) -> None:
        """Send full device list to cloud."""
        try:
            all_states = list(self.hass.states.async_all())
        except Exception as e:
            _LOGGER.warning("CloudLink: async_all() failed: %s", e)
            return
        
        sample = [s.entity_id for s in all_states[:5]]
        _LOGGER.warning(
            "CloudLink: sync - hass.states has %d entities, sample=%s",
            len(all_states), sample,
        )
        
        devices = []
        for state in all_states:
            try:
                devices.append({
                    "entity_id": state.entity_id,
                    "domain": state.domain,
                    "name": state.name,
                    "state": state.state,
                    "attributes": dict(state.attributes),
                })
            except Exception as e:
                _LOGGER.warning("CloudLink: error reading state %s: %s", 
                              getattr(state, "entity_id", "?"), e)
        
        if not devices:
            _LOGGER.warning("CloudLink: NO devices to sync! states.async_all() returned %d", len(all_states))
            return  # Don't send empty sync (it would delete cloud devices)
        
        msg = json.dumps({"type": "device_sync", "devices": devices})
        _LOGGER.warning("CloudLink: sending device_sync with %d devices (%d bytes)", 
                       len(devices), len(msg))
        
        try:
            await self.ws.send(msg)
            _LOGGER.warning("CloudLink: ✓ device_sync sent successfully")
        except Exception as e:
            _LOGGER.warning("CloudLink: ✗ failed to send device_sync: %s", e)
            raise

    async def _on_state_changed(self, event: Event) -> None:
        """Forward HA state changes to cloud."""
        new = event.data.get("new_state")
        old = event.data.get("old_state")
        if not new or (old and old.state == new.state):
            return
        if not self.ws:
            return
        try:
            payload = json.dumps({
                "type": "state_change",
                "entity_id": new.entity_id,
                "state": new.state,
                "attributes": dict(new.attributes),
            })
            await self.ws.send(payload)
        except ConnectionClosed:
            _LOGGER.warning("CloudLink: state change failed - connection closed")
        except Exception:
            _LOGGER.exception("CloudLink: failed to send state change")

    async def _handle_message(self, msg: dict) -> None:
        msg_type = msg.get("type")
        if msg_type == "ping":
            await self.ws.send(json.dumps({"type": "pong"}))
        elif msg_type == "device_action":
            await self._execute_action(msg)
        else:
            _LOGGER.debug("CloudLink: unknown message type: %s", msg_type)

    async def _execute_action(self, msg: dict) -> None:
        """Execute an HA service call from a device action.

        Falls back through several strategies if the primary service is not found:
        1. As requested (e.g., domain.toggle)
        2. For button domain: button.press
        3. Generic homeassistant.toggle (works for most domains)
        4. homeassistant.turn_on / turn_off
        """
        domain = msg.get("domain")
        service = msg.get("service")
        entity_id = msg.get("entity_id")
        data = dict(msg.get("data") or {})

        if not all([domain, service, entity_id]):
            _LOGGER.warning("CloudLink: incomplete action: %s", msg)
            return

        if "entity_id" not in data:
            data["entity_id"] = entity_id

        # Build fallback chain
        attempts = [(service, data)]
        if domain == "button" and service != "press":
            attempts.append(("press", dict(data)))
        attempts.append(("homeassistant.toggle", {"entity_id": entity_id}))
        if service != "turn_on":
            attempts.append(("homeassistant.turn_on", {"entity_id": entity_id}))
        if service != "turn_off":
            attempts.append(("homeassistant.turn_off", {"entity_id": entity_id}))

        for svc, d in attempts:
            _LOGGER.warning("CloudLink: attempting %s.%s on %s", domain, svc, entity_id)
            try:
                await self.hass.services.async_call(
                    domain, svc, d, blocking=False,
                )
                _LOGGER.warning("CloudLink: OK %s.%s on %s", domain, svc, entity_id)
                return
            except Exception as e:
                err = str(e).lower()
                if "not found" in err or "servicenotfound" in err:
                    _LOGGER.warning("CloudLink: %s.%s not available, trying next", domain, svc)
                    continue
                _LOGGER.exception("CloudLink: %s.%s failed (non-recoverable)", domain, svc)
                return

        _LOGGER.warning("CloudLink: all attempts failed for %s (entity: %s)", domain, entity_id)
