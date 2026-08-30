"""CloudLink integration for Home Assistant."""
import asyncio
import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .cloud_client import CloudClient
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)
PLATFORMS = []


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up CloudLink from a config entry."""
    cloud_url = entry.data["cloud_url"]
    cloud_token = entry.data["cloud_token"]

    client = CloudClient(hass, cloud_url, cloud_token)
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = client
    
    # CRITICAL: use asyncio.ensure_future (not hass.async_create_task) so the
    # background task is truly fire-and-forget and does NOT block HA startup.
    client.task = asyncio.ensure_future(client.start())
    
    _LOGGER.warning("CloudLink: setup completed, task started for %s", cloud_url)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    client: CloudClient = hass.data[DOMAIN].pop(entry.entry_id, None)
    if client:
        client._stopped = True
        if client.task and not client.task.done():
            client.task.cancel()
    return True
