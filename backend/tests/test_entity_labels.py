"""Cloud-side slot labelling of HA entities.

The App used to identify "which entity is the indicator light" by
matching Chinese words in the display name — renamable, localised, and
different per integration. Since plugin v0.2.0 we get `platform` and
`unique_id`, which carry the semantics outright:

  huawei_smarthome    ..._power_switch / ..._indicator_switch
  xiaomi_home         ..._on_p_2_1 / ..._physical_controls_locked_p_7_1

These tests pin that mapping, and above all pin what it must NOT do:
`main` drives the button that actually switches hardware, so it is only
ever assigned on an integration-specific match — never by a generic
heuristic, and never to a xiaomi `on` switch outside siid 2 (a plug
exposes four other `on` switches: charge protection, countdown, power
limit, over-consumption alert).
"""
import pytest

from app.services.entity_labels import assign_slots, entity_slot


class E:
    """Minimal stand-in for DeviceEntity (assign_slots only reads attrs)."""

    def __init__(self, entity_id, domain, platform=None, unique_id=None, odc=None):
        self.entity_id = entity_id
        self.domain = domain
        self.platform = platform
        self.unique_id = unique_id
        self.original_device_class = odc


HW = "huawei_smarthome"
XI = "xiaomi_home"


def hw(suffix):
    return f"9q3ofmn67l4ff6c6qbbb95g_03bd417e-8db0-47f5-a94e-c0de6a92321e_{suffix}"


def xi(tail):
    return f"xiaomi_home.cuco_cn_2051041278_v3_{tail}"


# --------------------------------------------------------------------------- #
#  huawei_smarthome
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize("suffix,expected", [
    ("power_switch", "main"),
    ("indicator_switch", "indicator"),
    ("child_lock_switch", "child_lock"),
    ("active_power", "power"),
    ("energy_total", "energy"),
    ("mains_voltage", "voltage"),
    ("line_current", "current"),
    ("firmware_version", "firmware"),
    ("overload_warning", "problem"),
    ("device_malfunction", "problem"),
])
def test_huawei_suffix_maps_to_slot(suffix, expected):
    assert entity_slot(HW, hw(suffix), "switch", None) == expected


def test_huawei_unknown_suffix_falls_back_to_device_class():
    """An unmapped huawei entity still gets its HA device_class."""
    assert entity_slot(HW, hw("something_new"), "sensor", "power") == "power"


# --------------------------------------------------------------------------- #
#  xiaomi_home
# --------------------------------------------------------------------------- #

def test_xiaomi_main_switch_is_siid_2():
    assert entity_slot(XI, xi("on_p_2_1"), "switch", "switch") == "main"


@pytest.mark.parametrize("tail", [
    "on_p_4_1",    # charge protection
    "on_p_8_1",    # countdown off
    "on_p_9_1",    # max power limit
    "on_p_10_1",   # over-consumption alert
])
def test_xiaomi_other_on_switches_are_not_main(tail):
    """The plug's service is siid 2; the rest must never be `main`."""
    assert entity_slot(XI, xi(tail), "switch", "switch") != "main"


@pytest.mark.parametrize("tail,expected", [
    ("physical_controls_locked_p_7_1", "child_lock"),
    ("electric_power_p_11_2", "power"),
    ("power_consumption_p_11_1", "energy"),
    ("temperature_p_12_2", "temperature"),
    ("fault_p_2_3", "problem"),
    ("relative_humidity_p_2_1", "humidity"),
    ("battery_level_p_2_3", "battery"),
])
def test_xiaomi_semantic_maps_to_slot(tail, expected):
    assert entity_slot(XI, xi(tail), "sensor", None) == expected


def test_xiaomi_indicator_do_not_disturb_is_not_indicator():
    """The plug has no indicator *switch* — only a do-not-disturb window.

    Matching it as `indicator` would put a switch on the wrong entity.
    """
    assert entity_slot(XI, xi("mode_p_3_2"), "switch", "switch") != "indicator"


# --------------------------------------------------------------------------- #
#  Fallbacks and safety
# --------------------------------------------------------------------------- #

def test_no_registry_fields_means_no_label():
    """Pre-0.2.0 plugin: caller falls back to its own rules."""
    assert entity_slot(None, None, "switch", None) is None
    assert entity_slot(HW, None, "switch", None) is None


def test_generic_integration_uses_device_class_only():
    assert entity_slot("some_integration", "abc-123", "sensor", "voltage") == "voltage"
    assert entity_slot("some_integration", "abc-123", "sensor", None) is None


def test_main_is_never_guessed_from_device_class():
    """`main` controls hardware — device_class alone must not produce it."""
    for odc in ("switch", "outlet", None):
        assert entity_slot("some_integration", "abc-123", "switch", odc) != "main"


# --------------------------------------------------------------------------- #
#  Device-level assignment
# --------------------------------------------------------------------------- #

def test_exclusive_slot_goes_to_first_match_only():
    ents = [
        E("sensor.a_power", "sensor", HW, hw("active_power"), "power"),
        E("sensor.b_power", "sensor", HW, hw("other_power"), "power"),
    ]
    slots = assign_slots(ents)
    assert slots == {"sensor.a_power": "power"}


def test_two_problem_sensors_are_both_labelled():
    """A plug reports overload and malfunction separately; the App keeps
    the first for the slot row and folds the rest away."""
    ents = [
        E("binary_sensor.overload", "binary_sensor", HW, hw("overload_warning"), "problem"),
        E("binary_sensor.malfunction", "binary_sensor", HW, hw("device_malfunction"), "problem"),
    ]
    assert set(assign_slots(ents).values()) == {"problem"}


def test_unlabelled_entities_are_absent():
    ents = [E("button.call", "button", HW, hw("voip_call"), None)]
    assert assign_slots(ents) == {}


# --------------------------------------------------------------------------- #
#  Through the API
# --------------------------------------------------------------------------- #

@pytest.mark.asyncio
async def test_slot_is_exposed_by_api(client, user_with_instance, db_session):
    from sqlalchemy import select

    from app.models.device import DeviceEntity
    from app.models.ha_instance import HAInstance

    inst = (
        await db_session.execute(
            select(HAInstance).where(HAInstance.user_id == user_with_instance.id)
        )
    ).scalar_one()
    db_session.add(DeviceEntity(
        ha_instance_id=inst.id,
        entity_id="switch.plug_main",
        domain="switch",
        name="华为智能插座 电源开关",
        state="on",
        attributes={},
        platform=HW,
        unique_id=hw("power_switch"),
    ))
    await db_session.commit()

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]

    resp = await client.get("/api/devices", headers={
        "Authorization": f"Bearer {token}",
    })
    assert resp.status_code == 200
    flat = {e["entity_id"]: e for dev in resp.json() for e in dev.get("entities", [])}
    assert flat["switch.plug_main"]["slot"] == "main"
    # fixture entity has no registry fields -> null, not missing
    assert flat["light.living_room"]["slot"] is None
