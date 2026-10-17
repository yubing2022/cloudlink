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
    # Activatable items that are user-defined actions, not physical
    # devices. They have no controllable state in the device-list
    # sense (just a "last triggered" timestamp), and triggering them
    # from a phone app is almost never what the user wants — if it
    # is, they can do it from HA's own UI. Cases seen in the wild
    # (e.g. WorkBuddy registering "automation.workbuddy_*" entries
    # for push notifications) just pollute the device list.
    "automation", "script",
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
    # Activatable items (script + automation moved to INTERNAL_DOMAINS
    # because they're user-defined actions, not physical devices)
    "scene",
    # Input helpers (commonly used to drive dashboards / automations)
    "input_boolean", "input_number", "input_select", "input_text",
})
