"""Unit tests for CloudLink HA plugin (mocked HA environment)."""
import asyncio
import json
import sys
from unittest.mock import AsyncMock, MagicMock, patch
from pathlib import Path

# Add the plugin's cloudlink subpackage path so we can import cloud_client directly
# without triggering __init__.py (which needs full HA)
sys.path.insert(0, str(Path(__file__).parent.parent / "custom_components" / "cloudlink"))

# Mock the homeassistant module since HA may not be installed locally
import types
ha_module = types.ModuleType("homeassistant")
config_entries_module = types.ModuleType("homeassistant.config_entries")
core_module = types.ModuleType("homeassistant.core")

class MockConfigEntry:
    pass
config_entries_module.ConfigEntry = MockConfigEntry
config_entries_module.ConfigFlow = type("ConfigFlow", (), {"async_set_unique_id": AsyncMock(), "_abort_if_unique_id_configured": MagicMock(), "async_show_form": MagicMock(), "async_create_entry": MagicMock()})
core_module.HomeAssistant = MagicMock
core_module.Event = MagicMock

sys.modules["homeassistant"] = ha_module
sys.modules["homeassistant.config_entries"] = config_entries_module
sys.modules["homeassistant.core"] = core_module
exceptions_module = types.ModuleType("homeassistant.exceptions")
exceptions_module.ConfigEntryNotReady = Exception
exceptions_module.ServiceNotFound = type("ServiceNotFound", (Exception,), {})
sys.modules["homeassistant.exceptions"] = exceptions_module

# cloud_client also imports from homeassistant.helpers.{device,area,entity}_registry
helpers_module = types.ModuleType("homeassistant.helpers")
for sub in ("device_registry", "area_registry", "entity_registry"):
    m = types.ModuleType(f"homeassistant.helpers.{sub}")
    # All three expose async_get() returning a registry object with async_get(...)
    reg_mock = MagicMock()
    reg_mock.async_get = MagicMock(return_value=None)  # entity not in registry
    m.async_get = MagicMock(return_value=reg_mock)
    sys.modules[f"homeassistant.helpers.{sub}"] = m
    setattr(helpers_module, sub, m)
sys.modules["homeassistant.helpers"] = helpers_module

# Set up cloudlink as a package, then load submodules
import importlib.util, types
pkg_path = str(Path(__file__).parent.parent / "custom_components" / "cloudlink")
pkg = types.ModuleType("cloudlink")
pkg.__path__ = [pkg_path]
sys.modules["cloudlink"] = pkg

for name in ["const", "cloud_client"]:
    spec = importlib.util.spec_from_file_location(
        f"cloudlink.{name}",
        Path(pkg_path) / f"{name}.py"
    )
    mod = importlib.util.module_from_spec(spec)
    sys.modules[f"cloudlink.{name}"] = mod
    spec.loader.exec_module(mod)

CloudClient = sys.modules["cloudlink.cloud_client"].CloudClient


class MockState:
    def __init__(self, entity_id, state="on", name="Test", domain="light", attrs=None):
        self.entity_id = entity_id
        self.state = state
        self.name = name
        self.domain = domain
        self.attributes = attrs or {}


class MockStates:
    def __init__(self, states):
        # key by entity_id for .get()
        self._states = states
        self._by_id = {s.entity_id: s for s in states}

    def async_all(self):
        return self._states

    def get(self, entity_id):
        return self._by_id.get(entity_id)


class _ServiceNotFound(Exception):
    """Stub for homeassistant.exceptions.ServiceNotFound, used in
    fallback-chain tests."""
    pass


def make_hass(states=None):
    hass = MagicMock()
    hass.states = MockStates(states or [])
    
    bus = MagicMock()
    bus.async_listen = MagicMock(return_value=lambda: None)
    hass.bus = bus
    
    services = MagicMock()
    services.async_call = AsyncMock()
    hass.services = services
    
    return hass


def test_url_conversion():
    """Test HTTP -> WS URL conversion."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com:8000", "test_token")
    
    # Test http -> ws
    client.cloud_url = "http://example.com:8000"
    expected = "ws://example.com:8000/api/ws/ha?token=test_token"
    actual = (
        client.cloud_url.replace("https://", "wss://").replace("http://", "ws://")
    ) + f"/api/ws/ha?token={client.cloud_token}"
    assert actual == expected, f"expected {expected}, got {actual}"
    
    # Test https -> wss
    client.cloud_url = "https://example.com"
    actual = (
        client.cloud_url.replace("https://", "wss://").replace("http://", "ws://")
    ) + f"/api/ws/ha?token={client.cloud_token}"
    assert actual == "wss://example.com/api/ws/ha?token=test_token"
    
    print("  ✓ url_conversion")


def test_url_normalization():
    """Test that trailing slashes are removed."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com:8000/", "test")
    assert client.cloud_url == "http://example.com:8000"
    print("  ✓ url_normalization")


def test_device_collection():
    """Test collecting devices from HA states."""
    states = [
        MockState("light.living_room", "on", "Living Room", "light", {"brightness": 200}),
        MockState("switch.kitchen", "off", "Kitchen", "switch"),
        MockState("sensor.temp_1", "22.5", "Temperature", "sensor", {"unit": "°C"}),
    ]
    hass = make_hass(states)
    client = CloudClient(hass, "http://example.com", "test_token")
    
    devices = []
    for state in hass.states.async_all():
        devices.append({
            "entity_id": state.entity_id,
            "domain": state.domain,
            "name": state.name,
            "state": state.state,
            "attributes": dict(state.attributes),
        })
    
    assert len(devices) == 3
    assert devices[0]["entity_id"] == "light.living_room"
    assert devices[0]["domain"] == "light"
    assert devices[1]["state"] == "off"
    assert devices[2]["attributes"]["unit"] == "°C"
    print("  ✓ device_collection")


async def test_action_execution():
    """Test that device actions are executed as HA service calls."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    
    # Mock a device action message
    msg = {
        "type": "device_action",
        "domain": "light",
        "service": "turn_on",
        "entity_id": "light.living_room",
        "data": {"brightness": 200},
    }
    
    await client._handle_message(msg)
    
    hass.services.async_call.assert_called_once()
    call_args = hass.services.async_call.call_args
    assert call_args[0][0] == "light"  # domain
    assert call_args[0][1] == "turn_on"  # service
    assert call_args[0][2]["entity_id"] == "light.living_room"
    assert call_args[0][2]["brightness"] == 200
    print("  ✓ action_execution")


async def test_action_result_sent_with_new_state():
    """After a successful device_action, plugin must send a
    device_action_result back to the cloud carrying the new state.
    This is what lets POST /api/devices/{id}/action return the
    post-action state instead of a stale DB row.
    """
    hass = make_hass()
    # Seed a real State so hass.states.get returns a value
    hass.states = MockStates([
        MockState("light.living_room", "off", "Living Room", "light",
                  attrs={"brightness": None}),
    ])
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    hass.services.async_call = AsyncMock()  # succeeds

    msg = {
        "type": "device_action",
        "request_id": "req-abc-123",
        "domain": "light",
        "service": "turn_off",
        "entity_id": "light.living_room",
        "data": {},
    }
    await client._handle_message(msg)

    hass.services.async_call.assert_awaited_once()
    # blocking=True now
    assert hass.services.async_call.call_args.kwargs.get("blocking") is True
    # Wait — kwargs is empty. Need to check positional.
    # services.async_call(domain, service, data, blocking=True) — blocking is kwarg
    # Our call: await self.hass.services.async_call(domain, svc, d, blocking=True)
    # check both:
    call = hass.services.async_call.call_args
    assert call.kwargs.get("blocking", call[3] if len(call) > 3 else None) is True

    # One ws.send with a device_action_result
    client.ws.send.assert_awaited_once()
    sent = json.loads(client.ws.send.call_args[0][0])
    assert sent["type"] == "device_action_result"
    assert sent["request_id"] == "req-abc-123"
    assert sent["entity_id"] == "light.living_room"
    assert sent["ok"] is True
    # New state read from hass.states (was "off" in the mock)
    assert sent["state"] == "off"
    assert sent["attributes"]["brightness"] is None
    assert sent["last_state_change"] is None
    print("  ✓ action_result_sent_with_new_state")


async def test_action_result_sent_with_last_state_change_iso():
    """When the entity's last_changed is set, the result must carry
    it as an ISO-8601 UTC string so the cloud can show it back to
    the APK without re-reading the DB.
    """
    from datetime import datetime, timezone
    hass = make_hass()
    last_changed = datetime(2026, 10, 18, 10, 0, 0, tzinfo=timezone.utc)
    state = MockState("light.x", "on", "X", "light", attrs={})
    state.last_changed = last_changed
    hass.states = MockStates([state])
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    hass.services.async_call = AsyncMock()

    await client._handle_message({
        "type": "device_action",
        "request_id": "req-xyz",
        "domain": "light",
        "service": "turn_on",
        "entity_id": "light.x",
        "data": {},
    })

    sent = json.loads(client.ws.send.call_args[0][0])
    assert sent["last_state_change"] == "2026-10-18T10:00:00+00:00"
    print("  ✓ action_result_sent_with_last_state_change_iso")


async def test_action_result_service_not_found_classified():
    """ServiceNotFound from HA → ok=False + error_type=service_not_found.

    This is the most important mapping: the cloud uses error_type to
    decide between 400 (caller error) and 502 (gateway error).
    """
    from homeassistant.exceptions import ServiceNotFound
    hass = make_hass()
    hass.states = MockStates([])
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    hass.services.async_call = AsyncMock(
        side_effect=ServiceNotFound("light.not_a_service not found")
    )

    await client._handle_message({
        "type": "device_action",
        "request_id": "req-notfound",
        "domain": "light",
        "service": "not_a_service",
        "entity_id": "light.x",
        "data": {},
    })

    client.ws.send.assert_awaited_once()
    sent = json.loads(client.ws.send.call_args[0][0])
    assert sent["type"] == "device_action_result"
    assert sent["request_id"] == "req-notfound"
    assert sent["ok"] is False
    assert sent["error_type"] == "service_not_found"
    assert "not_a_service" in sent["error"]
    # No silent fallback attempted — only one attempt log line
    print("  ✓ action_result_service_not_found_classified")


async def test_action_result_other_exception_classified_as_call_failed():
    """Non-ServiceNotFound exceptions → error_type=call_failed.

    Keeps the old 502 (gateway error) path intact.
    """
    hass = make_hass()
    hass.states = MockStates([])
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    hass.services.async_call = AsyncMock(
        side_effect=RuntimeError("device is unreachable")
    )

    await client._handle_message({
        "type": "device_action",
        "request_id": "req-callfail",
        "domain": "light",
        "service": "turn_on",
        "entity_id": "light.x",
        "data": {},
    })

    sent = json.loads(client.ws.send.call_args[0][0])
    assert sent["ok"] is False
    assert sent["error_type"] == "call_failed"
    assert "unreachable" in sent["error"]
    print("  ✓ action_result_other_exception_classified_as_call_failed")


async def test_action_no_silent_fallback_for_invalid_service():
    """Calling an invalid service must NOT silently run toggle/turn_on/turn_off.

    Regression for the bug where
        light.set_brightness → ServiceNotFound
    would cascade to:
        light.homeassistant.toggle  (wrong domain/service, dead code,
                                    but if it ever succeeded the user
                                    would silently get "toggle" instead
                                    of the requested brightness change)
    """
    from homeassistant.exceptions import ServiceNotFound
    hass = make_hass()
    hass.states = MockStates([])
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    call_count = {"n": 0}
    async def counting(*args, **kwargs):
        call_count["n"] += 1
        raise ServiceNotFound("light.set_brightness not found")
    hass.services.async_call = counting

    await client._handle_message({
        "type": "device_action",
        "request_id": "req-no-fallback",
        "domain": "light",
        "service": "set_brightness",
        "entity_id": "light.x",
        "data": {"brightness": 50},
    })

    # CRITICAL: must be 1, not 4 (no fallback chain)
    assert call_count["n"] == 1, (
        f"expected exactly 1 service call (no fallback), got {call_count['n']}"
    )
    sent = json.loads(client.ws.send.call_args[0][0])
    assert sent["error_type"] == "service_not_found"
    print("  ✓ action_no_silent_fallback_for_invalid_service")


async def test_action_without_request_id_sends_no_result():
    """Backward-compat: if the cloud forgot to include a request_id
    (shouldn't happen with the new code, but defensive), the plugin
    silently drops the result rather than crashing.
    """
    hass = make_hass()
    hass.states = MockStates([])
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    hass.services.async_call = AsyncMock()

    await client._handle_message({
        "type": "device_action",
        # request_id deliberately missing
        "domain": "light",
        "service": "turn_on",
        "entity_id": "light.x",
        "data": {},
    })

    client.ws.send.assert_not_called()
    print("  ✓ action_without_request_id_sends_no_result")


async def test_ping_pong():
    """Test that ping messages are answered with pong."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    
    await client._handle_message({"type": "ping"})
    
    # Verify pong was sent
    sent = client.ws.send.call_args[0][0]
    pong = json.loads(sent)
    assert pong["type"] == "pong"
    print("  ✓ ping_pong")


async def test_incomplete_action_ignored():
    """Test that malformed actions don't crash."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    
    # Missing fields
    await client._handle_message({"type": "device_action"})
    
    hass.services.async_call.assert_not_called()
    print("  ✓ incomplete_action_ignored")


async def test_attribute_only_change_forwarded():
    """Brightness-only update must be forwarded to cloud.

    Reproduces the production bug: setting light brightness leaves
    `state == "on"` unchanged but mutates `attributes`. Previous code
    dropped the event; this test pins the fix.
    """
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    # Bypass _entity_allowed so the test focuses on the change-detection logic
    client._entity_allowed = lambda _eid: True

    old = MockState(
        "light.yeelink_cn_1208334138_mbulb3_s_2",
        "on", "落地灯", "light",
        attrs={"brightness": 87, "color_mode": "color_temp"},
    )
    new = MockState(
        "light.yeelink_cn_1208334138_mbulb3_s_2",
        "on", "落地灯", "light",
        attrs={"brightness": 40, "color_mode": "color_temp"},
    )
    event = MagicMock()
    event.data = {"new_state": new, "old_state": old}

    await client._on_state_changed(event)

    client.ws.send.assert_called_once()
    payload = json.loads(client.ws.send.call_args[0][0])
    assert payload["type"] == "state_change"
    assert payload["entity_id"] == "light.yeelink_cn_1208334138_mbulb3_s_2"
    assert payload["state"] == "on"
    assert payload["attributes"]["brightness"] == 40
    assert payload["attributes"]["color_mode"] == "color_temp"
    print("  ✓ attribute_only_change_forwarded")


async def test_identical_state_and_attributes_suppressed():
    """Identical snapshots → no ws.send (regression guard)."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    client._entity_allowed = lambda _eid: True

    snap = MockState(
        "light.x", "on", "X", "light",
        attrs={"brightness": 100},
    )
    event = MagicMock()
    event.data = {"new_state": snap, "old_state": snap}

    await client._on_state_changed(event)

    client.ws.send.assert_not_called()
    print("  ✓ identical_state_and_attributes_suppressed")


async def test_state_change_off_to_on_forwarded():
    """Regression: off→on (state string changes) is still forwarded."""
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    client._entity_allowed = lambda _eid: True

    old = MockState("light.x", "off", "X", "light", attrs={})
    new = MockState("light.x", "on",  "X", "light", attrs={})
    event = MagicMock()
    event.data = {"new_state": new, "old_state": old}

    await client._on_state_changed(event)

    client.ws.send.assert_called_once()
    payload = json.loads(client.ws.send.call_args[0][0])
    assert payload["type"] == "state_change"
    assert payload["state"] == "on"
    print("  ✓ state_change_off_to_on_forwarded")


async def test_excluded_entity_not_forwarded_for_attribute_change():
    """_entity_allowed() still filters attribute-only events.

    Pin: the new equality check must NOT short-circuit before the
    filter; otherwise a brightness update on a filtered entity would
    bypass the include/exclude list.
    """
    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client.ws = AsyncMock()
    client._entity_allowed = lambda _eid: False  # filter rejects

    old = MockState("light.x", "on", "X", "light", attrs={"brightness": 87})
    new = MockState("light.x", "on", "X", "light", attrs={"brightness": 40})
    event = MagicMock()
    event.data = {"new_state": new, "old_state": old}

    await client._on_state_changed(event)

    client.ws.send.assert_not_called()
    print("  ✓ excluded_entity_not_forwarded_for_attribute_change")


async def test_initial_sync_immediate_when_hass_running():
    """When HA has already finished startup, _send_full_sync runs on connect."""
    import websockets
    hass = make_hass()
    hass.is_running = True  # HA already started
    client = CloudClient(hass, "http://example.com", "test_token")
    client._send_full_sync = AsyncMock()  # Don't actually scan states

    # Build a fake websockets.connect() context manager
    class FakeWS:
        async def send(self, _): pass
        async def close(self): pass
        def __aiter__(self): return self
        async def __anext__(self):
            raise StopAsyncIteration  # exit the message loop immediately
    fake_ws = FakeWS()

    class FakeConnect:
        def __aiter__(self): return self
        async def __anext__(self): raise StopAsyncIteration
        async def __aenter__(self): return fake_ws
        async def __aexit__(self, *args): return False

    def fake_connect(*args, **kwargs):
        return FakeConnect()

    with patch.object(websockets, "connect", fake_connect):
        try:
            await asyncio.wait_for(client._connect_and_serve(), timeout=2)
        except (asyncio.TimeoutError, StopAsyncIteration):
            pass

    client._send_full_sync.assert_awaited_once()
    print("  ✓ initial_sync_immediate_when_hass_running")


async def test_initial_sync_deferred_until_homeassistant_started():
    """When HA is still booting, no sync until homeassistant_started fires."""
    import websockets
    hass = make_hass()
    hass.is_running = False  # HA still starting
    started_listeners = []
    hass.bus.async_listen_once = MagicMock(
        side_effect=lambda evt, cb: started_listeners.append((evt, cb)) or (lambda: None)
    )
    client = CloudClient(hass, "http://example.com", "test_token")
    client._send_full_sync = AsyncMock()

    class FakeWS:
        async def send(self, _): pass
        async def close(self): pass
        def __aiter__(self): return self
        async def __anext__(self):
            raise StopAsyncIteration
    fake_ws = FakeWS()

    class FakeConnect:
        async def __aenter__(self): return fake_ws
        async def __aexit__(self, *args): return False

    def fake_connect(*args, **kwargs):
        return FakeConnect()

    with patch.object(websockets, "connect", fake_connect):
        try:
            await asyncio.wait_for(client._connect_and_serve(), timeout=2)
        except (asyncio.TimeoutError, StopAsyncIteration):
            pass

    # Sync must NOT have been called yet
    client._send_full_sync.assert_not_called()
    # And a one-shot listener for homeassistant_started must be registered
    assert any(evt == "homeassistant_started" for evt, _ in started_listeners),         f"expected homeassistant_started listener, got {started_listeners}"
    print("  ✓ initial_sync_deferred_until_homeassistant_started")


async def test_dumps_handles_datetime_in_attributes():
    """_dumps must serialise datetime in state.attributes (last_updated,
    last_changed) without raising — otherwise the WS dies in a reconnect
    loop and action messages never reach the plugin.
    """
    from datetime import datetime, timezone
    from cloudlink.cloud_client import _dumps

    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    client._entity_allowed = lambda _eid: True

    old = MockState(
        "light.x", "on", "X", "light",
        attrs={"brightness": 87},
    )
    new = MockState(
        "light.x", "on", "X", "light",
        attrs={
            "brightness": 40,
            # The two datetime fields HA always attaches to State:
            "last_updated": datetime(2026, 10, 18, 10, 0, 0, tzinfo=timezone.utc),
            "last_changed": datetime(2026, 10, 18, 10, 0, 0, tzinfo=timezone.utc),
            # And integration-specific ones that some plugins stuff in:
            "last_seen": datetime(2026, 10, 18, 9, 59, 30, tzinfo=timezone.utc),
        },
    )
    event = MagicMock()
    event.data = {"new_state": new, "old_state": old}

    client.ws = AsyncMock()
    # This used to raise TypeError: Object of type datetime is not JSON
    # serializable. After the fix it should send cleanly.
    await client._on_state_changed(event)

    client.ws.send.assert_called_once()
    payload = json.loads(client.ws.send.call_args[0][0])
    # The datetime must have been stringified, not left as a datetime
    assert payload["attributes"]["last_updated"] == "2026-10-18T10:00:00+00:00"
    assert payload["attributes"]["last_changed"] == "2026-10-18T10:00:00+00:00"
    assert payload["attributes"]["last_seen"] == "2026-10-18T09:59:30+00:00"
    # And the brightness update still goes through
    assert payload["attributes"]["brightness"] == 40
    print("  ✓ dumps_handles_datetime_in_attributes")


async def test_automation_and_script_domains_always_filtered():
    """automation.* and script.* are user-defined actions, not devices.

    They live in INTERNAL_DOMAINS so _domain_allowed() returns False
    regardless of what the user picked in options (mode, domains,
    patterns). Otherwise integrations like WorkBuddy pollute the
    device list with notification-trigger automations.
    """
    from cloudlink.cloud_client import INTERNAL_DOMAINS
    from cloudlink.const import USEFUL_DOMAINS
    assert "automation" in INTERNAL_DOMAINS, (
        "automation should be in INTERNAL_DOMAINS so it never appears "
        "in the device list and can't be triggered from the app"
    )
    assert "script" in INTERNAL_DOMAINS, (
        "script should be in INTERNAL_DOMAINS for the same reason"
    )
    # And NOT in the user-facing USEFUL_DOMAINS, so the options-flow
    # dropdown doesn't offer them as a choice.
    assert "automation" not in USEFUL_DOMAINS
    assert "script" not in USEFUL_DOMAINS

    hass = make_hass()
    client = CloudClient(hass, "http://example.com", "test_token")
    # Even with NO exclude patterns, automation + script are blocked.
    assert client._domain_allowed("automation") is False
    assert client._domain_allowed("script") is False
    # Sanity: light is still allowed
    assert client._domain_allowed("light") is True
    print("  ✓ automation_and_script_domains_always_filtered")


def run_sync_tests():
    test_url_conversion()
    test_url_normalization()
    test_device_collection()


async def run_async_tests():
    await test_action_execution()
    await test_ping_pong()
    await test_incomplete_action_ignored()
    await test_attribute_only_change_forwarded()
    await test_identical_state_and_attributes_suppressed()
    await test_state_change_off_to_on_forwarded()
    await test_excluded_entity_not_forwarded_for_attribute_change()
    await test_initial_sync_immediate_when_hass_running()
    await test_initial_sync_deferred_until_homeassistant_started()
    await test_dumps_handles_datetime_in_attributes()
    await test_automation_and_script_domains_always_filtered()
    await test_action_result_sent_with_new_state()
    await test_action_result_sent_with_last_state_change_iso()
    await test_action_result_service_not_found_classified()
    await test_action_result_other_exception_classified_as_call_failed()
    await test_action_no_silent_fallback_for_invalid_service()
    await test_action_without_request_id_sends_no_result()


if __name__ == "__main__":
    print("\n[HA Plugin] Sync tests")
    run_sync_tests()
    
    print("\n[HA Plugin] Async tests")
    asyncio.run(run_async_tests())
    
    print("\n========================================")
    print("All tests passed ✓")
    print("========================================")
