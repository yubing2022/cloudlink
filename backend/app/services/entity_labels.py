"""Semantic slot labels for HA entities.

The App renders a device's detail page from a slot template (see
Android `DeviceTemplate.kt`): "power", "power reading", "child lock",
"indicator light"... Each slot needs exactly one entity to fill it.

Which entity that is used to be guessed from `name` — a localised
display name the user can rename, and whose wording differs per
integration. Since plugin v0.2.0 we also get `platform` and
`unique_id`, and those turn out to carry the semantics outright:

  huawei_smarthome   <deviceid>_<uuid>_power_switch
                     <deviceid>_<uuid>_indicator_switch
                     <deviceid>_<uuid>_active_power
  xiaomi_home        xiaomi_home.<model>_on_p_2_1
                     xiaomi_home.<model>_physical_controls_locked_p_7_1
                     xiaomi_home.<model>_electric_power_p_11_2

So the cloud can label entities from stable identifiers instead of
matching Chinese words. Two design consequences:

- **Labelling lives here, not in the plugin.** Rules need tuning
  constantly (one plug category already has 9 slots); changing a rule
  here takes effect on the next sync for every user, whereas a plugin
  change would need a custom-component update and an HA restart.
- **A missing label is safe.** `None` means "don't know", and the App
  falls back to its own template rules. Nothing is worse than guessing
  wrong on `main`, which is the one slot that actually controls
  hardware — so `main` is only ever assigned by an integration-specific
  rule that we have seen in real data, never by a generic heuristic.
"""

from __future__ import annotations

import re

# --------------------------------------------------------------------------- #
#  Slot ids — keep in sync with Android DeviceTemplate.kt
# --------------------------------------------------------------------------- #

MAIN = "main"
POWER = "power"
ENERGY = "energy"
VOLTAGE = "voltage"
CURRENT = "current"
TEMPERATURE = "temperature"
HUMIDITY = "humidity"
BATTERY = "battery"
CHILD_LOCK = "child_lock"
INDICATOR = "indicator"
PROBLEM = "problem"
CONNECTIVITY = "connectivity"
FIRMWARE = "firmware"

#: Slots that at most one entity of a device may hold.
EXCLUSIVE_SLOTS = frozenset({MAIN, POWER, ENERGY, VOLTAGE, CURRENT, TEMPERATURE,
                             HUMIDITY, BATTERY, CHILD_LOCK, INDICATOR})

# --------------------------------------------------------------------------- #
#  Rules
# --------------------------------------------------------------------------- #

#: HA's own device_class -> slot. Applies to every integration, and is
#: the only signal available for integrations we have no rule for.
#: Note huawei_smarthome sets it too (active_power -> power), so this
#: doubles as a cross-check on the per-platform rules below.
_DEVICE_CLASS_SLOTS = {
    "power": POWER,
    "energy": ENERGY,
    "voltage": VOLTAGE,
    "current": CURRENT,
    "temperature": TEMPERATURE,
    "humidity": HUMIDITY,
    "battery": BATTERY,
    "problem": PROBLEM,
    "connectivity": CONNECTIVITY,
}

#: huawei_smarthome: unique_id = "<deviceid>_<uuid>_<semantic>".
#: The semantic part is plain English and needs no further parsing.
_HUAWEI_SUFFIX_SLOTS = {
    "power_switch": MAIN,
    "indicator_switch": INDICATOR,
    "child_lock_switch": CHILD_LOCK,
    "active_power": POWER,
    "energy_total": ENERGY,
    "mains_voltage": VOLTAGE,
    "line_current": CURRENT,
    "firmware_version": FIRMWARE,
    "overload_warning": PROBLEM,
    "device_malfunction": PROBLEM,
    "online": CONNECTIVITY,
}

#: xiaomi_home: unique_id = "xiaomi_home.<model>_<semantic>_<p|a|e>_<siid>_<piid>"
_XIAOMI_TAIL_RE = re.compile(r"_(?P<kind>[pae])_(?P<siid>\d+)_(?P<piid>\d+)$")

#: semantic name -> slot, matched as a suffix of the semantic part
#: (which still carries the model prefix, e.g. "cuco_cn_..._v3_on").
_XIAOMI_SEMANTIC_SLOTS = {
    "physical_controls_locked": CHILD_LOCK,
    "electric_power": POWER,
    "power_consumption": ENERGY,
    "temperature": TEMPERATURE,
    "relative_humidity": HUMIDITY,
    "battery_level": BATTERY,
    "fault": PROBLEM,
}

#: A xiaomi_home plug exposes several `on` switches — siid 4/8/9/10 are
#: charge protection, countdown-off, max-power limit and over-consumption
#: alert. siid 2 is the plug's own switch in miot-spec, and is the only
#: one we are willing to call `main`. Seen in real data: cuco.plug.v3 has
#: on_p_2_1 (the plug) alongside four other `on` switches.
_XIAOMI_MAIN_SIID = 2


def _huawei_suffix(unique_id: str) -> str | None:
    """Extract the semantic tail of a huawei_smarthome unique_id."""
    parts = unique_id.split("_")
    # <deviceid>_<uuid>_<semantic> — the uuid is the only part with "-"
    if len(parts) >= 3 and "-" in parts[1]:
        return "_".join(parts[2:])
    return None


def _xiaomi_semantic(unique_id: str) -> tuple[str | None, int | None]:
    """Return (semantic, siid) for a xiaomi_home unique_id."""
    if not unique_id.startswith("xiaomi_home."):
        return None, None
    body = unique_id.split(".", 1)[1]
    m = _XIAOMI_TAIL_RE.search(body)
    if not m:
        return None, None
    return body[: m.start()], int(m.group("siid"))


def entity_slot(
    platform: str | None,
    unique_id: str | None,
    domain: str | None,
    original_device_class: str | None,
) -> str | None:
    """Label one entity, or return None if we can't tell.

    Order matters: integration-specific rules first (they are the only
    ones allowed to assign `main`), then HA's own device_class.
    """
    if not platform or not unique_id:
        # Pre-0.2.0 plugin, or an entity with no registry entry: the
        # App falls back to its local template rules.
        return None

    if platform == "huawei_smarthome":
        slot = _HUAWEI_SUFFIX_SLOTS.get(_huawei_suffix(unique_id) or "")
        if slot:
            return slot

    elif platform == "xiaomi_home":
        semantic, siid = _xiaomi_semantic(unique_id)
        if semantic:
            if semantic.endswith("_on") and siid == _XIAOMI_MAIN_SIID and domain == "switch":
                return MAIN
            for suffix, slot in _XIAOMI_SEMANTIC_SLOTS.items():
                if semantic.endswith(f"_{suffix}") or semantic == suffix:
                    return slot

    return _DEVICE_CLASS_SLOTS.get(original_device_class or "")


def assign_slots(entities: list) -> dict[str, str]:
    """Label a device's entities: {entity_id: slot}.

    Exclusive slots go to the first matching entity only — two entities
    claiming `main` would mean the App controls whichever it happens to
    render first, which is exactly the failure this whole exercise is
    meant to prevent. Entities we can't label are simply absent from the
    result.
    """
    out: dict[str, str] = {}
    taken: set[str] = set()
    for e in entities:
        slot = entity_slot(
            getattr(e, "platform", None),
            getattr(e, "unique_id", None),
            getattr(e, "domain", None),
            getattr(e, "original_device_class", None),
        )
        if not slot:
            continue
        if slot in EXCLUSIVE_SLOTS and slot in taken:
            continue
        out[e.entity_id] = slot
        if slot in EXCLUSIVE_SLOTS:
            taken.add(slot)
    return out
