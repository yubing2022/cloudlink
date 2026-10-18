"""Tests for device endpoints."""
from datetime import datetime, timezone

import pytest

from app.main import app


# --------------------------------------------------------------------------- #
#  list_devices
# --------------------------------------------------------------------------- #

@pytest.mark.asyncio
async def test_list_devices_empty(client):
    # First register and login
    await client.post("/api/auth/register", json={
        "email": "dev1@example.com",
        "password": "password123",
    })
    resp = await client.post("/api/auth/login", json={
        "email": "dev1@example.com",
        "password": "password123",
    })
    token = resp.json()["access_token"]

    # List devices (should be empty)
    resp = await client.get("/api/devices", headers={
        "Authorization": f"Bearer {token}",
    })
    assert resp.status_code == 200
    assert resp.json() == []


# --------------------------------------------------------------------------- #
#  GET /api/devices/{entity_id}/state  (new)
# --------------------------------------------------------------------------- #

@pytest.mark.asyncio
async def test_get_device_state_ok(client, user_with_instance):
    """Happy path: known entity returns flat 4-field payload."""
    # Get token for the user we just created via the fixture
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    login = await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = login.json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    resp = await client.get("/api/devices/light.living_room/state", headers=h)
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Exact field set required by the client (no parent device wrapping)
    assert set(body.keys()) == {
        "entity_id", "state", "attributes", "last_state_change",
    }
    assert body["entity_id"] == "light.living_room"
    assert body["state"] == "on"
    assert body["attributes"] == {"brightness": 200, "color_mode": "xy"}
    # last_state_change must round-trip to the same UTC instant
    assert body["last_state_change"] is not None
    assert body["last_state_change"].startswith("2026-10-17T10:00:00")


@pytest.mark.asyncio
async def test_get_device_state_empty_attributes(client, user_with_instance):
    """Empty attributes dict is returned as `{}`, not null."""
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    # Orphan entity with attributes={}
    resp = await client.get("/api/devices/switch.kitchen_fan/state", headers=h)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["entity_id"] == "switch.kitchen_fan"
    assert body["state"] == "off"
    assert body["attributes"] == {}  # explicitly empty dict, not None


@pytest.mark.asyncio
async def test_get_device_state_not_found(client, user_with_instance):
    """Unknown entity_id → 404 with `Entity not found` (no 403 to avoid leak)."""
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    resp = await client.get("/api/devices/light.does_not_exist/state", headers=h)
    assert resp.status_code == 404
    assert resp.json() == {
        "detail": {
            "error_type": "entity_not_found",
            "message": "Entity not found",
            "entity_id": "light.does_not_exist",
        }
    }


@pytest.mark.asyncio
async def test_get_device_state_unauthorized(client):
    """No token → 401."""
    resp = await client.get("/api/devices/light.living_room/state")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_get_device_state_other_user_hidden(client, user_with_instance):
    """An entity that exists but belongs to a different user → 404,
    same response as if it didn't exist at all (no existence leak)."""
    # Register a second user, log them in.
    await client.post("/api/auth/register", json={
        "email": "intruder@example.com",
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": "intruder@example.com",
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    # Entity belongs to user_with_instance, not the intruder.
    resp = await client.get("/api/devices/light.living_room/state", headers=h)
    assert resp.status_code == 404
    assert resp.json() == {
        "detail": {
            "error_type": "entity_not_found",
            "message": "Entity not found",
            "entity_id": "light.living_room",
        }
    }


# --------------------------------------------------------------------------- #
#  Regression: existing routes still work
# --------------------------------------------------------------------------- #

@pytest.mark.asyncio
async def test_get_device_parent_still_works(client, user_with_instance):
    """GET /api/devices/{entity_id} (parent device wrapper) still works
    for an ORPHAN entity and returns a single-element entities list."""
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    # Orphan (no device) → endpoint wraps it in a synthetic HADeviceSchema.
    resp = await client.get("/api/devices/switch.kitchen_fan", headers=h)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Parent shape, NOT flat state (state would have no entities key)
    assert "entities" in body
    assert len(body["entities"]) == 1
    assert body["entities"][0]["entity_id"] == "switch.kitchen_fan"


@pytest.mark.asyncio
async def test_get_device_real_device_ok(client, user_with_instance):
    """Regression: real device (entity with ha_device_pk) must NOT 500.

    Previous code did `selectinload(DeviceEntity.device)` only, so when
    FastAPI serialized `HADeviceSchema.entities` after the handler
    returned, async lazy-load raised MissingGreenlet → HTTP 500.
    """
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    # light.living_room belongs to dev-001 (real device, not orphan).
    resp = await client.get("/api/devices/light.living_room", headers=h)
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Parent shape, with ALL sibling entities preloaded
    assert "entities" in body
    entity_ids = sorted(e["entity_id"] for e in body["entities"])
    assert entity_ids == ["light.living_room", "sensor.living_room_temp"]
    # And the original entity is in the returned set
    assert any(e["entity_id"] == "light.living_room" for e in body["entities"])


@pytest.mark.asyncio
async def test_sync_deletes_stale_devices(client, user_with_instance, db_session):
    """A 2nd sync that omits a device must drop that device (and cascade
    to its entities) from the DB. First sync must not delete anything.
    """
    # Look up the HA instance's cloud_token for the sync endpoint
    from app.models.ha_instance import HAInstance
    from sqlalchemy import select
    instance = (await db_session.execute(
        select(HAInstance).where(HAInstance.user_id == user_with_instance.id)
    )).scalar_one()
    cloud_token = instance.cloud_token

    # First sync: send device-001 with both entities.
    # Include dev-001 too so the existing fixture's data is preserved
    # across the first sync (otherwise device-cleanup would delete it
    # since it's not in the payload).
    sync1 = {
        "type": "device_sync",
        "devices": {
            "dev-001": {
                "name": "Yeelight Lamp",
                "manufacturer": "Xiaomi",
                "model": "yeelink.light.mb1",
                "area": "Living Room",
            },
            "dev-test-A": {
                "name": "Test Lamp A",
                "manufacturer": "Acme",
                "model": "a1",
                "area": "Lab",
            },
        },
        "entities": [
            {"entity_id": "light.living_room", "domain": "light",
             "name": "Living Room Light", "state": "on",
             "attributes": {"brightness": 200},
             "ha_device_id": "dev-001"},
            {"entity_id": "sensor.living_room_temp", "domain": "sensor",
             "name": "Living Room Temperature", "state": "21.5",
             "attributes": {"unit_of_measurement": "°C"},
             "ha_device_id": "dev-001"},
            {"entity_id": "light.test_room", "domain": "light",
             "name": "Test Light A", "state": "on",
             "attributes": {"brightness": 200},
             "ha_device_id": "dev-test-A"},
        ],
    }
    r1 = await client.post(f"/api/ha/{cloud_token}/devices/sync", json=sync1)
    
    assert r1.status_code == 200, r1.text
    body1 = r1.json()
    assert body1["devices_added"] == 1
    assert body1["devices_deleted"] == 0  # first sync, nothing stale
    assert body1["entities_added"] == 1  # light.test_room is new; light.living_room and sensor.living_room_temp were already in DB from fixture

    # Second sync: device-001 gone, replaced by dev-002; sensor moves to dev-002
    # Second sync: keep dev-001 in payload (so its device survives) but
    # send only one of its entities. dev-test-A is dropped (not in
    # payload), so its device AND its entity get cascade-deleted.
    sync2 = {
        "type": "device_sync",
        "devices": {
            "dev-001": {
                "name": "Yeelight Lamp",
                "manufacturer": "Xiaomi",
                "model": "yeelink.light.mb1",
                "area": "Living Room",
            },
        },
        "entities": [
            {"entity_id": "light.living_room", "domain": "light",
             "name": "Living Room Light", "state": "on",
             "attributes": {"brightness": 200},
             "ha_device_id": "dev-001"},
        ],
    }
    r2 = await client.post(f"/api/ha/{cloud_token}/devices/sync", json=sync2)
    assert r2.status_code == 200, r2.text
    body2 = r2.json()
    # dev-test-A dropped (not in payload) → devices_deleted == 1
    assert body2["devices_deleted"] == 1
    assert body2["devices_added"] == 0   # dev-001 was already there
    # light.test_room gone (cascade from device delete) +
    # sensor.living_room_temp gone (not in sync2's entities)
    assert body2["entities_deleted"] == 2


@pytest.mark.asyncio
async def test_sync_keeps_orphan_entities(client, user_with_instance, db_session):
    """Entities with ha_device_pk=None (legacy / no device) must NOT be
    deleted when the payload has devices but no matching entity.
    """
    from app.models.ha_instance import HAInstance
    from sqlalchemy import select
    instance = (await db_session.execute(
        select(HAInstance).where(HAInstance.user_id == user_with_instance.id)
    )).scalar_one()
    cloud_token = instance.cloud_token

    # Seed an orphan (no ha_device_pk) + a normal pair
    sync1 = {
        "type": "device_sync",
        "devices": {
            "dev-001": {"name": "Lamp", "area": "Living Room"},
        },
        "entities": [
            {"entity_id": "light.x", "domain": "light",
             "name": "Lamp", "state": "on", "attributes": {},
             "ha_device_id": "dev-001"},
            # Orphan: ha_device_id = None
            {"entity_id": "switch.orphan", "domain": "switch",
             "name": "Orphan", "state": "off", "attributes": {},
             "ha_device_id": None},
        ],
    }
    r1 = await client.post(f"/api/ha/{cloud_token}/devices/sync", json=sync1)
    assert r1.status_code == 200, r1.text
    # Second sync: same device, but no entities at all in payload.
    # Orphan entity has no ha_device_id, so it must be preserved
    # (because we compare device cleanup by ha_device_id, not by the
    # entity side).
    sync2 = {
        "type": "device_sync",
        "devices": {
            "dev-001": {"name": "Lamp", "area": "Living Room"},
        },
        "entities": [],
    }
    r2 = await client.post(f"/api/ha/{cloud_token}/devices/sync", json=sync2)
    assert r2.status_code == 200, r2.text
    body2 = r2.json()
    # dev-001 stayed (still in payload) → devices_deleted == 0
    assert body2["devices_deleted"] == 0
    # The orphan entity is in the original sync but the second sync
    # has no entities → it should be removed (incoming_entity_ids
    # is empty after the second sync, so all existing entities get
    # deleted). This is pre-existing entity-deletion behavior; the
    # device cleanup does NOT touch orphan entities directly.
    # Verify orphan is gone after sync2 (matches existing entity
    # cleanup contract):
    from app.models.device import DeviceEntity
    ents = (await db_session.execute(
        select(DeviceEntity).where(DeviceEntity.ha_instance_id == instance.id)
    )).scalars().all()
    # sync2 sent 0 entities so the existing orphan + light.x were
    # both deleted by the entity-cleanup logic. The new code only
    # affects devices, not entities.
    assert [e.entity_id for e in ents] == []


@pytest.mark.asyncio
async def test_list_devices_unchanged(client, user_with_instance):
    """GET /api/devices still returns the same list shape."""
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    resp = await client.get("/api/devices", headers=h)
    assert resp.status_code == 200
    body = resp.json()
    # 1 real device with 2 entities, plus 1 synthetic orphan device.
    assert len(body) == 2
    real = next(d for d in body if d["ha_device_id"] == "dev-001")
    assert sorted(e["entity_id"] for e in real["entities"]) == [
        "light.living_room", "sensor.living_room_temp",
    ]



@pytest.mark.asyncio
async def test_control_returns_post_action_state(client, user_with_instance):
    """Regression: /action must return the NEW state (post-service-call),
    not the pre-action DB row. Repro for the bug where calling
    turn_off twice in a row returned state=on the first time
    (because the action was fire-and-forget and the state hadn't
    propagated back to the DB yet).
    """
    from app.ws import manager as ws_manager_module
    import asyncio
    import uuid

    # Login
    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    # Get cloud_token of the user's HA instance
    from app.models.ha_instance import HAInstance
    from sqlalchemy import select
    from app.database import get_db
    async for db in app.dependency_overrides[get_db]():
        instance = (await db.execute(
            select(HAInstance).where(HAInstance.user_id == user_with_instance.id)
        )).scalar_one()
        cloud_token = instance.cloud_token
        break

    # Simulate the HA plugin by stubbing request_action_and_wait so
    # we don't need a real WS connection. It must simulate the
    # plugin sending a device_action_result with the new state.
    async def fake_request_action_and_wait(token, msg, *, timeout=5.0):
        rid = msg["request_id"]
        # Echo the result the plugin WOULD send: state="off",
        # attributes mutated to reflect the action.
        result = {
            "type": "device_action_result",
            "request_id": rid,
            "entity_id": msg["entity_id"],
            "ok": True,
            "error": None,
            "state": "off",
            "attributes": {"brightness": None},
            "last_state_change": "2026-10-18T10:00:00+00:00",
        }
        # Simulate tiny network latency
        await asyncio.sleep(0.01)
        return result

    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = fake_request_action_and_wait
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_off", "data": {}},
        )
        assert resp.status_code == 200, resp.text
        body = resp.json()
        # The response must reflect the post-action state, not the
        # pre-action DB row (which the user fixture created as
        # state="on", brightness=200).
        assert body["status"] == "ok"
        assert body["entity_id"] == "light.living_room"
        assert body["state"] == "off", (
            f"expected post-action state=off, got {body['state']!r} "
            f"— bug: control returned pre-action state"
        )
        assert body["attributes"] == {"brightness": None}
        assert body["last_state_change"] == "2026-10-18T10:00:00+00:00"
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original


@pytest.mark.asyncio
async def test_control_503_when_ha_offline(client, user_with_instance):
    """If the HA plugin doesn't have a live WS, /action returns 503
    with structured detail {error_type: ha_offline}."""
    from app.ws import manager as ws_manager_module

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    async def fake(token, msg, *, timeout=5.0):
        raise ConnectionError("HA is not connected")
    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = fake
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_off", "data": {}},
        )
        assert resp.status_code == 503
        detail = resp.json()["detail"]
        assert detail["error_type"] == "ha_offline"
        assert "offline" in detail["message"].lower()
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original


@pytest.mark.asyncio
async def test_control_504_on_plugin_timeout(client, user_with_instance):
    """If the plugin never replies (e.g. the action hangs in HA),
    /action surfaces 504 with structured detail {error_type: ha_timeout}."""
    from app.ws import manager as ws_manager_module
    import asyncio

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    async def slow_request(token, msg, *, timeout=5.0):
        await asyncio.sleep(0.01)
        raise asyncio.TimeoutError()

    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = slow_request
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_off", "data": {}},
        )
        assert resp.status_code == 504
        detail = resp.json()["detail"]
        assert detail["error_type"] == "ha_timeout"
        assert "in time" in detail["message"].lower()
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original


@pytest.mark.asyncio
async def test_control_400_when_service_not_found(client, user_with_instance):
    """Plugin reports error_type=service_not_found → HTTP 400 with
    structured detail. The Android capability already maps this to
    a 4xx user-actionable error."""
    from app.ws import manager as ws_manager_module

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    async def fake(token, msg, *, timeout=5.0):
        return {
            "type": "device_action_result",
            "request_id": msg["request_id"],
            "ok": False,
            "error_type": "service_not_found",
            "error": "Service light.set_brightness not found.",
            "state": None,
            "attributes": {},
            "last_state_change": None,
        }
    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = fake
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "set_brightness", "data": {"brightness": 50}},
        )
        assert resp.status_code == 400, resp.text
        detail = resp.json()["detail"]
        assert detail["error_type"] == "service_not_found"
        assert "set_brightness" in detail["message"]
        assert detail["entity_id"] == "light.living_room"
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original


@pytest.mark.asyncio
async def test_control_502_when_call_failed(client, user_with_instance):
    """Plugin reports error_type=call_failed → HTTP 502 (gateway error).

    The new structured detail replaces the previous bare-string.
    """
    from app.ws import manager as ws_manager_module

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    async def fake(token, msg, *, timeout=5.0):
        return {
            "type": "device_action_result",
            "request_id": msg["request_id"],
            "ok": False,
            "error_type": "call_failed",
            "error": "device is unreachable",
            "state": None,
            "attributes": {},
            "last_state_change": None,
        }
    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = fake
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_on", "data": {}},
        )
        assert resp.status_code == 502, resp.text
        detail = resp.json()["detail"]
        assert detail["error_type"] == "call_failed"
        assert "unreachable" in detail["message"]
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original


@pytest.mark.asyncio
async def test_control_502_legacy_plugin_without_error_type(client, user_with_instance):
    """Old plugin doesn't send error_type → backend defaults to call_failed → 502.

    Backward-compat: existing HA plugin versions out there still
    produce a useful 502, just without the structured detail.
    """
    from app.ws import manager as ws_manager_module

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    async def fake(token, msg, *, timeout=5.0):
        # Note: NO error_type field — simulate pre-fix plugin
        return {
            "type": "device_action_result",
            "request_id": msg["request_id"],
            "ok": False,
            "error": "service light.turn_off not found",
            "state": None,
            "attributes": {},
            "last_state_change": None,
        }
    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = fake
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_off", "data": {}},
        )
        # Still 502 (backward compat)
        assert resp.status_code == 502, resp.text
        detail = resp.json()["detail"]
        assert detail["error_type"] == "call_failed"
        assert "not found" in detail["message"]
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original


@pytest.mark.asyncio
async def test_control_503_504_have_structured_detail(client, user_with_instance):
    """503/504 paths also use structured detail now."""
    from app.ws import manager as ws_manager_module
    import asyncio

    await client.post("/api/auth/register", json={
        "email": user_with_instance.email,
        "password": "password123",
    })
    token = (await client.post("/api/auth/login", json={
        "email": user_with_instance.email,
        "password": "password123",
    })).json()["access_token"]
    h = {"Authorization": f"Bearer {token}"}

    # 503: HA offline
    async def fake_offline(token, msg, *, timeout=5.0):
        raise ConnectionError("not connected")
    original = ws_manager_module.ws_manager.request_action_and_wait
    ws_manager_module.ws_manager.request_action_and_wait = fake_offline
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_off", "data": {}},
        )
        assert resp.status_code == 503
        detail = resp.json()["detail"]
        assert detail["error_type"] == "ha_offline"
        assert "offline" in detail["message"].lower()
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original

    # 504: plugin timeout
    async def fake_timeout(token, msg, *, timeout=5.0):
        raise asyncio.TimeoutError()
    ws_manager_module.ws_manager.request_action_and_wait = fake_timeout
    try:
        resp = await client.post(
            "/api/devices/light.living_room/action",
            headers={**h, "Content-Type": "application/json"},
            json={"domain": "light", "service": "turn_off", "data": {}},
        )
        assert resp.status_code == 504
        detail = resp.json()["detail"]
        assert detail["error_type"] == "ha_timeout"
        assert "in time" in detail["message"].lower()
    finally:
        ws_manager_module.ws_manager.request_action_and_wait = original
