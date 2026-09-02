"""CloudLink integration for Home Assistant."""
import asyncio
import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .cloud_client import CloudClient
from .const import DOMAIN, INTERNAL_DOMAINS

_LOGGER = logging.getLogger(__name__)
PLATFORMS = []




def _resolve_filter(options: dict) -> tuple[set[str], set[str]]:
    """Resolve the single-mode filter (HomeKit-style) into include/exclude sets.

    The user picks ONE mode (include or exclude) and a list of domains.
    Anything else (domains not in USEFUL_DOMAINS, e.g. sun, person, weather)
    is unconditionally dropped at sync time — see INTERNAL_DOMAINS and the
    _domain_allowed check in CloudClient.

    Handles backwards-compat with the legacy {include_domains, exclude_domains}
    shape: whichever legacy list was non-empty wins.
    """
    if "mode" in options:
        mode = options.get("mode", "exclude")
        domains = set(options.get("domains") or [])
    else:
        if options.get("include_domains"):
            mode = "include"
            domains = set(options["include_domains"])
        elif options.get("exclude_domains"):
            mode = "exclude"
            domains = set(options["exclude_domains"])
        else:
            # Brand-new entry, never configured → ship defaults to keep the
            # user's app sane out of the box.
            mode = "exclude"
            domains = set(INTERNAL_DOMAINS)

    if mode == "include":
        return domains, set()
    return set(), domains


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up CloudLink from a config entry."""
    cloud_url = entry.data["cloud_url"]
    cloud_token = entry.data["cloud_token"]

    include_domains, exclude_domains = _resolve_filter(entry.options)

    client = CloudClient(
        hass, cloud_url, cloud_token,
        include_domains=list(include_domains),
        exclude_domains=list(exclude_domains),
    )
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = client

    # CRITICAL: use asyncio.ensure_future (not hass.async_create_task) so the
    # background task is truly fire-and-forget and does NOT block HA startup.
    client.task = asyncio.ensure_future(client.start())

    # Reload the entry when options change so the new filter takes effect.
    entry.async_on_unload(entry.add_update_listener(_async_update_listener))

    _LOGGER.warning("CloudLink: setup completed, task started for %s", cloud_url)
    return True


async def _async_update_listener(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Reload entry when options change (e.g. domain filter edited)."""
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    client: CloudClient = hass.data[DOMAIN].pop(entry.entry_id, None)
    if client:
        client._stopped = True
        if client.task and not client.task.done():
            client.task.cancel()
    return True
