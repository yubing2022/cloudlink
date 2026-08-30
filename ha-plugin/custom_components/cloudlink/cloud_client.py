"""WebSocket client for CloudLink cloud server.

Handles bidirectional communication between HA and cloud:
- Sends initial device list on connect
- Forwards HA state changes to cloud
- Receives device action commands from cloud and calls HA services
- Auto-reconnects with exponential backoff
"""
import asyncio
import json
import logging
from typing import Optional

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


class CloudClient:
    """Manages WebSocket connection to CloudLink cloud."""

    def __init__(self, hass: HomeAssistant, cloud_url: str, cloud_token: str):
        """Initialize the cloud client."""
        self.hass = hass
        self.cloud_url = cloud_url.rstrip("/")
        self.cloud_token = cloud_token
        self.ws: Optional = None
        self._stopped = False
        self._backoff = INITIAL_BACKOFF_SECONDS
        self._unsub_state = None

    async def start(self) -> None:
        """Start the connection loop."""
        _LOGGER.info("Starting CloudLink WebSocket client for %s", self.cloud_url)
        while not self._stopped:
            try:
                await self._connect_and_serve()
            except Exception as e:
                _LOGGER.exception("CloudLink error: %s", e)
            
            if self._stopped:
                break
            
            _LOGGER.info("Reconnecting in %d seconds...", self._backoff)
            await asyncio.sleep(self._backoff)
            self._backoff = min(self._backoff * 2, MAX_BACKOFF_SECONDS)

    async def stop(self) -> None:
        """Stop the connection."""
        _LOGGER.info("Stopping CloudLink client")
        self._stopped = True
        if self._unsub_state:
            self._unsub_state()
            self._unsub_state = None
        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass

    async def _connect_and_serve(self) -> None:
        """Connect to cloud and serve the WebSocket."""
        # Convert http(s) URL to ws(s)
        ws_url = (
            self.cloud_url
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        ) + f"/api/ws/ha?token={self.cloud_token}"
        
        _LOGGER.info("Connecting to %s...", ws_url.split("?")[0])
        
        async with websockets.connect(ws_url, ping_interval=20, ping_timeout=10) as ws:
            self.ws = ws
            self._backoff = INITIAL_BACKOFF_SECONDS  # Reset backoff on success
            
            # Send initial device sync
            await self._send_full_sync()
            
            # Subscribe to state changes
            self._unsub_state = self.hass.bus.async_listen(
                "state_changed", self._on_state_changed
            )
            
            _LOGGER.info("CloudLink connected and synced")
            
            # Serve messages
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    await self._handle_message(msg)
                except json.JSONDecodeError:
                    _LOGGER.warning("Invalid JSON from cloud: %r", raw[:200])
                except Exception:
                    _LOGGER.exception("Error handling message")

    async def _send_full_sync(self) -> None:
        """Send full device list to cloud."""
        devices = []
        for state in self.hass.states.async_all():
            devices.append({
                "entity_id": state.entity_id,
                "domain": state.domain,
                "name": state.name,
                "state": state.state,
                "attributes": dict(state.attributes),
            })
        await self.ws.send(json.dumps({
            "type": "device_sync",
            "devices": devices,
        }))
        _LOGGER.info("Synced %d devices to cloud", len(devices))

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
            _LOGGER.warning("State change failed: connection closed")
        except Exception:
            _LOGGER.exception("Failed to send state change")

    async def _handle_message(self, msg: dict) -> None:
        """Handle messages from cloud."""
        msg_type = msg.get("type")
        if msg_type == "ping":
            await self.ws.send(json.dumps({"type": "pong"}))
        elif msg_type == "device_action":
            await self._execute_action(msg)
        else:
            _LOGGER.debug("Unknown message type: %s", msg_type)

    async def _execute_action(self, msg: dict) -> None:
        """Execute an HA service call from a device action."""
        domain = msg.get("domain")
        service = msg.get("service")
        entity_id = msg.get("entity_id")
        data = msg.get("data", {})
        
        if not all([domain, service, entity_id]):
            _LOGGER.warning("Incomplete action: %s", msg)
            return
        
        # entity_id needs to be in service data
        if "entity_id" not in data:
            data["entity_id"] = entity_id
        
        _LOGGER.info(
            "Executing %s.%s on %s with %s",
            domain, service, entity_id, data,
        )
        try:
            await self.hass.services.async_call(
                domain,
                service,
                data,
                blocking=False,
            )
        except Exception:
            _LOGGER.exception("Failed to execute service %s.%s", domain, service)
