"""Config flow for CloudLink integration."""
import logging
import re
from typing import Any

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResult

from .const import DEFAULT_CLOUD_URL, DOMAIN

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
