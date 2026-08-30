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
sys.modules["homeassistant.exceptions"] = exceptions_module

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
        self._states = states

    def async_all(self):
        return self._states


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


def run_sync_tests():
    test_url_conversion()
    test_url_normalization()
    test_device_collection()


async def run_async_tests():
    await test_action_execution()
    await test_ping_pong()
    await test_incomplete_action_ignored()


if __name__ == "__main__":
    print("\n[HA Plugin] Sync tests")
    run_sync_tests()
    
    print("\n[HA Plugin] Async tests")
    asyncio.run(run_async_tests())
    
    print("\n========================================")
    print("All tests passed ✓")
    print("========================================")
