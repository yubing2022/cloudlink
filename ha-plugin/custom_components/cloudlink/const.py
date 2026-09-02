"""Constants for the CloudLink integration."""
DOMAIN = "cloudlink"
DEFAULT_CLOUD_URL = "http://118.31.225.109:8000"

# WebSocket reconnect backoff
INITIAL_BACKOFF_SECONDS = 5
MAX_BACKOFF_SECONDS = 60

# Heartbeat
HEARTBEAT_INTERVAL_SECONDS = 30

# Domains that are NEVER offered as filter choices in the options UI.
# These are pure informational / service / HA-internal types and are
# unconditionally dropped from sync regardless of user config.
# (Anything not in USEFUL_DOMAINS is also implicitly excluded — these
# names just spell out the most common ones for clarity.)
INTERNAL_DOMAINS = frozenset({
    # Read-only location / environment info
    "sun", "weather", "zone",
    # Location tracking
    "person",
    # Event entities (used by automations, no useful state to display)
    "event",
    # Lists / tasks / schedules
    "todo", "calendar", "schedule",
    # Voice / text services
    "tts", "stt", "conversation",
    # HA internals
    "persistent_notification", "update", "tag",
    # Notification dispatch (not a device)
    "notify",
    # Image processing (legacy)
    "image_processing",
})

# Domains that the user MAY pick as filter targets. These are the only
# ones shown in the options-flow dropdowns — anything else is filtered
# out of sync regardless of the user's choice. Restricted to entities
# that represent real, controllable devices or meaningful state.
USEFUL_DOMAINS = frozenset({
    # Read-only state display
    "binary_sensor", "sensor", "device_tracker",
    # Controllable devices
    "button", "climate", "cover", "fan", "humidifier",
    "light", "lock", "media_player", "number", "select",
    "switch", "text", "vacuum", "valve", "water_heater",
    # Activatable items
    "automation", "scene", "script",
    # Input helpers (commonly used to drive dashboards / automations)
    "input_boolean", "input_number", "input_select", "input_text",
})
