"""CloudLink HA Plugin - Cloud WebSocket client."""
import asyncio
import json
import logging

import websockets
from homeassistant.core import Event, HomeAssistant
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import entity_registry as er
from websockets.exceptions import ConnectionClosed

from .const import (
    DOMAIN,
    HEARTBEAT_INTERVAL_SECONDS,
    INTERNAL_DOMAINS,
    INITIAL_BACKOFF_SECONDS,
    MAX_BACKOFF_SECONDS,
)

_LOGGER = logging.getLogger(__name__)
# Force WARNING level so HA logs show our messages
_LOGGER.setLevel(logging.WARNING)


class CloudClient:
    """Manages WebSocket connection to CloudLink cloud."""

    def __init__(
        self,
        hass: HomeAssistant,
        cloud_url: str,
        cloud_token: str,
        include_domains: list[str] | None = None,
        exclude_domains: list[str] | None = None,
        exclude_entity_patterns: list[str] | None = None,
    ):
        import fnmatch
        self.hass = hass
        self.cloud_url = cloud_url.rstrip("/")
        self.cloud_token = cloud_token
        self.include_domains = set(include_domains or [])
        self.exclude_domains = set(exclude_domains or [])
        # Patterns use fnmatch glob syntax; empty list = no per-entity filter.
        self.exclude_entity_patterns = [
            p for p in (exclude_entity_patterns or []) if p.strip()
        ]
        self.ws = None
        self._stopped = False
        self._backoff = INITIAL_BACKOFF_SECONDS
        self._unsub_state = None
        self._unsub_started = None

    def _domain_allowed(self, domain: str) -> bool:
        """Apply include/exclude domain filter.

        - INTERNAL_DOMAINS (sun, person, weather, ...) are NEVER allowed,
          regardless of user config — pure informational / HA-internal types
          that have no business syncing to a phone app.
        - exclude_domains always wins (blacklist first)
        - include_domains, if non-empty, is a whitelist
        - if both empty: only INTERNAL_DOMAINS are dropped
        """
        if domain in INTERNAL_DOMAINS:
            return False
        if domain in self.exclude_domains:
            return False
        if self.include_domains and domain not in self.include_domains:
            return False
        return True

    def _entity_allowed(self, entity_id: str) -> bool:
        """Apply domain filter + entity_id pattern filter.

        Patterns are checked against entity_id via fnmatch (glob-style:
        *, ?, [seq] all work). Empty patterns list = pass-through.
        """
        if not self._domain_allowed(entity_id.split(".", 1)[0]):
            return False
        if self.exclude_entity_patterns:
            import fnmatch
            for pattern in self.exclude_entity_patterns:
                if fnmatch.fnmatch(entity_id, pattern):
                    return False
        return True

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
        """Send full state (devices + entities) to cloud.

        Filters out HA's "config" and "diagnostic" entities
        (e.g. stt, tts, update, repairs) - these aren't real devices.
        Real entities have entity_category = None.
        """
        try:
            all_states = list(self.hass.states.async_all())
        except Exception as e:
            _LOGGER.warning("CloudLink: async_all() failed: %s", e)
            return

        self._sync_count = getattr(self, "_sync_count", 0) + 1

        device_reg = dr.async_get(self.hass)
        area_reg = ar.async_get(self.hass)
        entity_reg = er.async_get(self.hass)

        # 1. Devices (from HA device registry)
        devices_dict: dict[str, dict] = {}
        for device in device_reg.devices.values():
            area_name = None
            if device.area_id:
                area_obj = area_reg.async_get_area(device.area_id)
                if area_obj:
                    area_name = area_obj.name
            devices_dict[device.id] = {
                "name": device.name or "Unknown",
                "manufacturer": device.manufacturer,
                "model": device.model,
                "area": area_name,
                "sw_version": device.sw_version,
                "hw_version": device.hw_version,
            }

        # 2. Entities (filtered by entity_category + user-configured domain filter)
        FILTERED_CONFIG = FILTERED_DIAGNOSTIC = FILTERED_DOMAIN = FILTERED_PATTERN = 0
        entities = []
        for state in all_states:
            if not self._entity_allowed(state.entity_id):
                # Distinguish domain vs pattern drops for logging clarity
                if not self._domain_allowed(state.domain):
                    FILTERED_DOMAIN += 1
                else:
                    FILTERED_PATTERN += 1
                continue
            ent_reg_entry = entity_reg.async_get(state.entity_id)
            category = ent_reg_entry.entity_category if ent_reg_entry else None
            if category == "config":
                FILTERED_CONFIG += 1
                continue
            if category == "diagnostic":
                FILTERED_DIAGNOSTIC += 1
                continue
            ha_device_id = (
                ent_reg_entry.device_id
                if ent_reg_entry and ent_reg_entry.device_id
                else None
            )
            entities.append({
                "entity_id": state.entity_id,
                "domain": state.domain,
                "name": state.name,
                "state": state.state,
                "attributes": dict(state.attributes),
                "ha_device_id": ha_device_id,
                "entity_category": category,
            })

        if FILTERED_CONFIG or FILTERED_DIAGNOSTIC:
            _LOGGER.warning(
                "CloudLink: filtered %d config + %d diagnostic entities (stt/tts/update/repairs/...)",
                FILTERED_CONFIG, FILTERED_DIAGNOSTIC,
            )
        if FILTERED_DOMAIN:
            _LOGGER.warning(
                "CloudLink: filtered %d entities by domain filter (include=%s exclude=%s)",
                FILTERED_DOMAIN, sorted(self.include_domains), sorted(self.exclude_domains),
            )
        if FILTERED_PATTERN:
            _LOGGER.warning(
                "CloudLink: filtered %d entities by entity_id pattern (patterns=%s)",
                FILTERED_PATTERN, self.exclude_entity_patterns,
            )
        _LOGGER.warning(
            "CloudLink: sync #%d - %d devices, %d entities (after filter)",
            self._sync_count, len(devices_dict), len(entities),
        )

        if not entities:
            _LOGGER.warning("CloudLink: NO entities after filter (would delete all on cloud), skipping")
            return

        msg = json.dumps({
            "type": "device_sync",
            "devices": devices_dict,
            "entities": entities,
        })
        try:
            await self.ws.send(msg)
            _LOGGER.warning("CloudLink: device_sync sent OK (%d bytes)", len(msg))
        except Exception as e:
            _LOGGER.warning("CloudLink: failed to send device_sync: %s", e)
            raise


    async def _on_state_changed(self, event: Event) -> None:
        """Forward HA state changes to cloud."""
        new = event.data.get("new_state")
        old = event.data.get("old_state")
        if not new or (old and old.state == new.state):
            return
        if not self.ws:
            return
        # Apply domain + entity-id pattern filter so excluded/notincluded
        # entities never reach cloud
        if not self._entity_allowed(new.entity_id):
            return
        # Also include ha_device_id so backend can update the right device
        ha_device_id = None
        try:
            entity_reg = er.async_get(self.hass)
            ent_reg_entry = entity_reg.async_get(new.entity_id)
            if ent_reg_entry:
                ha_device_id = ent_reg_entry.device_id
        except Exception:
            pass
        try:
            payload = json.dumps({
                "type": "state_change",
                "entity_id": new.entity_id,
                "state": new.state,
                "attributes": dict(new.attributes),
                "ha_device_id": ha_device_id,
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
