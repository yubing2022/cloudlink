"""CloudLink integration for Home Assistant.

Connects HA to a CloudLink cloud server via WebSocket:
- HA -> cloud: device list sync, state change reports
- cloud -> HA: device action commands (service calls)
"""
import logging
from datetime import timedelta

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryNotReady

from .cloud_client import CloudClient
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

PLATFORMS = []  # This is a service-only integration (no entities exposed)

SCAN_INTERVAL = timedelta(seconds=30)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up CloudLink from a config entry."""
    cloud_url = entry.data["cloud_url"]
    cloud_token = entry.data["cloud_token"]

    client = CloudClient(hass, cloud_url, cloud_token)
    
    # Store client for cleanup
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = client
    
    # Start the WebSocket connection in background
    hass.async_create_task(client.start())
    
    # Listen for HA options updates
    entry.async_on_unload(entry.add_update_listener(_async_update_listener))
    
    _LOGGER.info("CloudLink integration started for %s", cloud_url)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    client: CloudClient = hass.data[DOMAIN].pop(entry.entry_id)
    await client.stop()
    _LOGGER.info("CloudLink integration unloaded")
    return True


async def _async_update_listener(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Handle options updates."""
    await hass.config_entries.async_reload(entry.entry_id)
