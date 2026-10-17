"""Tests for device endpoints."""
from datetime import datetime, timezone

import pytest


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
    assert resp.json() == {"detail": "Entity not found"}


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
    assert resp.json() == {"detail": "Entity not found"}


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
