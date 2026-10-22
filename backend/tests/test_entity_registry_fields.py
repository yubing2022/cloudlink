"""Entity-registry provenance fields (platform / unique_id / original_*).

HA plugin v0.2.0+ ships these so the cloud can label an entity by the
integration that created it instead of matching a localised display name
(`name` is renamable, and `attributes.device_class` is missing on many
integrations — e.g. all three switches of the Huawei plug have none).

The contract pinned here:
  - the columns exist on DeviceEntity and are nullable (old plugins /
    pre-upgrade rows must not break)
  - they survive a round-trip through GET /api/devices, so App,
    capability and MCP consumers can read them
  - entities that never received them stay NULL
"""
import pytest
from sqlalchemy import select

from app.models.device import DeviceEntity
from app.models.ha_instance import HAInstance


REGISTRY_FIELDS = ("platform", "unique_id", "original_device_class", "original_name")


def test_device_entity_declares_registry_columns():
    cols = DeviceEntity.__table__.columns
    for name in REGISTRY_FIELDS:
        assert name in cols, f"{name} column missing"
        assert cols[name].nullable, f"{name} must be nullable (old plugins)"


@pytest.mark.asyncio
async def test_registry_fields_round_trip_through_api(
    client, user_with_instance, db_session,
):
    """A synced entity exposes its registry fields; an older one is NULL."""
    inst = (
        await db_session.execute(
            select(HAInstance).where(HAInstance.user_id == user_with_instance.id)
        )
    ).scalar_one()

    db_session.add(DeviceEntity(
        ha_instance_id=inst.id,
        entity_id="switch.plug_indicator",
        domain="switch",
        name="华为智能插座 指示灯",
        state="on",
        attributes={},
        # what the plugin now sends
        platform="huawei_smartplug",
        unique_id="plug-abc-indicator",
        original_device_class=None,
        original_name="Indicator Light",
    ))
    await db_session.commit()

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    login = await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = login.json()["access_token"]

    resp = await client.get("/api/devices", headers={
        "Authorization": f"Bearer {token}",
    })
    assert resp.status_code == 200

    flat = {
        e["entity_id"]: e
        for dev in resp.json()
        for e in dev.get("entities", [])
    }
    assert "switch.plug_indicator" in flat

    new_ent = flat["switch.plug_indicator"]
    assert new_ent["platform"] == "huawei_smartplug"
    assert new_ent["unique_id"] == "plug-abc-indicator"
    assert new_ent["original_name"] == "Indicator Light"
    assert new_ent["original_device_class"] is None

    # Pre-upgrade entity (fixture-created, never carried these keys)
    old_ent = flat["light.living_room"]
    for name in REGISTRY_FIELDS:
        assert name in old_ent, f"{name} missing from payload"
        assert old_ent[name] is None
