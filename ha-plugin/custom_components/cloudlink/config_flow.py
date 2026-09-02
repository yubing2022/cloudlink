"""Config flow for CloudLink integration."""
import logging
import re
from typing import Any

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import HomeAssistant, callback
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers import selector

from .const import DEFAULT_CLOUD_URL, DOMAIN, USEFUL_DOMAINS

_LOGGER = logging.getLogger(__name__)

STEP_USER_DATA_SCHEMA = vol.Schema({
    vol.Required("cloud_url", default=DEFAULT_CLOUD_URL): str,
    vol.Required("cloud_token"): str,
})


def _validate_cloud_url(url: str) -> str:
    """Validate and normalize cloud URL."""
    url = url.strip().rstrip("/")
    if not re.match(r"^https?://", url):
        raise ValueError("URL must start with http:// or https://")
    return url


class CloudLinkConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for CloudLink."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Handle the initial step."""
        if user_input is not None:
            try:
                user_input["cloud_url"] = _validate_cloud_url(user_input["cloud_url"])
            except ValueError as err:
                errors = {"base": "invalid_url"}
                return self.async_show_form(
                    step_id="user",
                    data_schema=STEP_USER_DATA_SCHEMA,
                    errors=errors,
                )

            # Use cloud_token first 8 chars as unique ID
            await self.async_set_unique_id(user_input["cloud_token"][:16])
            self._abort_if_unique_id_configured()
            
            return self.async_create_entry(
                title=f"CloudLink ({user_input['cloud_url']})",
                data=user_input,
            )

        return self.async_show_form(
            step_id="user",
            data_schema=STEP_USER_DATA_SCHEMA,
        )

    @staticmethod
    @callback
    def async_get_options_flow(
        config_entry: config_entries.ConfigEntry,
    ) -> config_entries.OptionsFlow:
        """Return the options flow handler."""
        return CloudLinkOptionsFlow()



class CloudLinkOptionsFlow(config_entries.OptionsFlow):
    """Handle CloudLink options (single-mode domain filter, HomeKit-style)."""

    # Migrate legacy per-list options into the unified shape. Anything not
    # in USEFUL_DOMAINS is silently dropped during migration.
    async def _normalised_current(self) -> dict[str, Any]:
        options = self.config_entry.options
        if "mode" in options or "entity_id_patterns" in options:
            return {
                "mode": options.get("mode", "exclude"),
                "domains": options.get("domains", []),
                "entity_id_patterns": options.get("entity_id_patterns", []),
            }
        # Legacy {include_domains, exclude_domains}
        if options.get("include_domains"):
            return {"mode": "include", "domains": options["include_domains"]}
        if options.get("exclude_domains"):
            return {"mode": "exclude", "domains": options["exclude_domains"]}
        return {"mode": "exclude", "domains": [], "entity_id_patterns": []}

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Manage the options."""
        if user_input is not None:
            # Normalise entity_id_patterns from multiline string to list of globs
            raw_patterns = user_input.get("entity_id_patterns", "")
            if isinstance(raw_patterns, str):
                patterns = [
                    line.strip()
                    for line in raw_patterns.splitlines()
                    if line.strip()
                ]
            else:
                # Already a list (legacy / programmatic submit)
                patterns = list(raw_patterns)
            user_input["entity_id_patterns"] = patterns
            return self.async_create_entry(title="", data=user_input)

        current = await self._normalised_current()

        # Build the domain list: only USEFUL domains that actually have
        # entities in this HA. Anything else (sun, person, weather, ...)
        # is hardcoded-dropped at sync time and never appears here.
        available_domains = sorted({
            state.domain
            for state in self.hass.states.async_all()
            if state.domain in USEFUL_DOMAINS
        })

        schema = vol.Schema({
            vol.Required(
                "mode", default=current["mode"],
            ): selector.SelectSelector(
                selector.SelectSelectorConfig(
                    options=[
                        {"value": "exclude", "label": "Exclude selected domains"},
                        {"value": "include", "label": "Include only selected domains"},
                    ],
                    multiple=False,
                    mode=selector.SelectSelectorMode.DROPDOWN,
                ),
            ),
            vol.Optional(
                "domains", default=current["domains"],
            ): selector.SelectSelector(
                selector.SelectSelectorConfig(
                    options=available_domains,
                    multiple=True,
                    mode=selector.SelectSelectorMode.DROPDOWN,
                ),
            ),
            vol.Optional(
                "entity_id_patterns",
                default=("\n".join(current.get("entity_id_patterns") or [])),
                description=(
                    "One fnmatch glob per line (e.g. sensor.backup_* or *_epson_*). "
                    "Entities whose entity_id matches any pattern are excluded from sync, "
                    "regardless of the domain filter above."
                ),
            ): selector.TextSelector(
                selector.TextSelectorConfig(multiline=True),
            ),
        })

        return self.async_show_form(
            step_id="init",
            data_schema=schema,
        )
