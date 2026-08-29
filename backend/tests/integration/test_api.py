#!/usr/bin/env python3
"""
CloudLink API Integration Tests.

Verifies the deployed API conforms to expectations.

Usage:
    python test_api.py [BASE_URL]
    python test_api.py                       # default: http://118.31.225.109
    python test_api.py http://localhost:8000 # local

Exit code: 0 on success, 1 on failure.
"""
import asyncio
import sys
import time
import uuid

import httpx
import websockets

DEFAULT_BASE_URL = "http://118.31.225.109"

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE_URL
WS_URL = BASE_URL.replace("http://", "ws://").replace("https://", "wss://")

PASS = 0
FAIL = 0
ERRORS: list[tuple[str, str]] = []


def log_pass(name: str) -> None:
    global PASS
    PASS += 1
    print(f"  \033[32m✓\033[0m {name}")


def log_fail(name: str, msg: str) -> None:
    global FAIL
    FAIL += 1
    print(f"  \033[31m✗\033[0m {name}: {msg}")
    ERRORS.append((name, msg))


def assert_eq(actual, expected, name: str) -> None:
    if actual == expected:
        log_pass(name)
    else:
        log_fail(name, f"expected {expected!r}, got {actual!r}")


# ─────────────────────────────────────────────────────────────────────────────
# Test helpers

async def setup_user(c: httpx.AsyncClient, label: str = "test") -> tuple[str, str]:
    """Register + login a new user. Returns (access_token, refresh_token)."""
    email = f"{label}-{uuid.uuid4().hex[:8]}@cloudlink.test"
    password = "testpass123456"
    await c.post("/api/auth/register", json={"email": email, "password": password})
    r = await c.post("/api/auth/login", json={"email": email, "password": password})
    r.raise_for_status()
    body = r.json()
    return body["access_token"], body["refresh_token"]


async def setup_ha_instance(c: httpx.AsyncClient, token: str, name: str = "Test Home") -> dict:
    """Register an HA instance. Returns instance dict with cloud_token."""
    r = await c.post(
        "/api/ha/register",
        json={"name": name, "ha_token": "fake_long_lived_token_xxxxxx"},
        headers={"Authorization": f"Bearer {token}"},
    )
    r.raise_for_status()
    return r.json()


# ─────────────────────────────────────────────────────────────────────────────
# Test 1: Authentication

async def test_auth() -> None:
    print("\n[1/5] Authentication")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10) as c:
        email = f"auth-{uuid.uuid4().hex[:8]}@cloudlink.test"
        password = "testpass123456"

        # Register
        r = await c.post("/api/auth/register", json={"email": email, "password": password})
        assert_eq(r.status_code, 201, "register returns 201")
        tokens = r.json()
        assert_eq("access_token" in tokens, True, "register returns access_token")
        assert_eq("refresh_token" in tokens, True, "register returns refresh_token")
        assert_eq(tokens.get("token_type"), "bearer", "register returns token_type=bearer")

        # Login
        r = await c.post("/api/auth/login", json={"email": email, "password": password})
        assert_eq(r.status_code, 200, "login returns 200")
        access_token = r.json()["access_token"]
        refresh_token = r.json()["refresh_token"]

        # /me with valid token
        r = await c.get("/api/auth/me", headers={"Authorization": f"Bearer {access_token}"})
        assert_eq(r.status_code, 200, "/me with valid token returns 200")
        me = r.json()
        assert_eq(me["email"], email, "/me returns correct email")

        # /me without token
        r = await c.get("/api/auth/me")
        assert_eq(r.status_code, 401, "/me without token returns 401")

        # /me with invalid token
        r = await c.get("/api/auth/me", headers={"Authorization": "Bearer invalid.token.here"})
        assert_eq(r.status_code, 401, "/me with invalid token returns 401")

        # Refresh token
        r = await c.post("/api/auth/refresh", json={"refresh_token": refresh_token})
        assert_eq(r.status_code, 200, "refresh returns 200")
        new_tokens = r.json()
        assert_eq("access_token" in new_tokens, True, "refresh returns new access_token")
        assert_eq(new_tokens["access_token"] != access_token, True, "refreshed access_token differs")

        # Refresh with access token (should fail - type mismatch)
        r = await c.post("/api/auth/refresh", json={"refresh_token": access_token})
        assert_eq(r.status_code, 401, "refresh with access token returns 401")

        # Duplicate register
        r = await c.post("/api/auth/register", json={"email": email, "password": password})
        assert_eq(r.status_code, 409, "duplicate register returns 409")

        # Wrong password
        r = await c.post("/api/auth/login", json={"email": email, "password": "wrongpass"})
        assert_eq(r.status_code, 401, "wrong password returns 401")

        # Non-existent user
        r = await c.post("/api/auth/login", json={"email": "no-such-user@x.com", "password": "whatever"})
        assert_eq(r.status_code, 401, "non-existent user returns 401")

        # Invalid email format
        r = await c.post("/api/auth/register", json={"email": "notanemail", "password": "longenough"})
        assert_eq(r.status_code, 422, "invalid email returns 422")

        # Short password
        r = await c.post(
            "/api/auth/register",
            json={"email": f"short-{uuid.uuid4().hex[:6]}@x.com", "password": "short"},
        )
        assert_eq(r.status_code, 422, "short password returns 422")


# ─────────────────────────────────────────────────────────────────────────────
# Test 2: HA Instance management (user-facing)

async def test_ha_instance() -> None:
    print("\n[2/5] HA Instance management")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10) as c:
        access_token, _ = await setup_user(c, "ha")
        headers = {"Authorization": f"Bearer {access_token}"}

        # Register HA instance
        r = await c.post(
            "/api/ha/register",
            json={"name": "Test Home", "ha_token": "fake_token_for_testing_xx"},
            headers=headers,
        )
        assert_eq(r.status_code, 201, "register HA returns 201")
        instance = r.json()
        assert_eq("cloud_token" in instance, True, "register returns cloud_token")
        assert_eq(instance.get("is_online"), False, "new instance starts offline")
        assert_eq(instance.get("name"), "Test Home", "register returns correct name")

        # cloud_token should be a long random string
        assert_eq(len(instance["cloud_token"]) > 20, True, "cloud_token is sufficiently long")
        instance_id = instance["id"]

        # List instances
        r = await c.get("/api/ha/instances", headers=headers)
        assert_eq(r.status_code, 200, "list instances returns 200")
        instances = r.json()
        assert_eq(len(instances), 1, "list shows 1 instance")
        assert_eq(instances[0]["device_count"], 0, "new instance has 0 devices")

        # Register another instance
        r = await c.post(
            "/api/ha/register",
            json={"name": "Test Home 2", "ha_token": "another_fake_token_xxx"},
            headers=headers,
        )
        assert_eq(r.status_code, 201, "register second instance returns 201")
        second_id = r.json()["id"]

        r = await c.get("/api/ha/instances", headers=headers)
        assert_eq(len(r.json()), 2, "list shows 2 instances")

        # Delete instance
        r = await c.delete(f"/api/ha/instances/{second_id}", headers=headers)
        assert_eq(r.status_code, 204, "delete returns 204")

        r = await c.get("/api/ha/instances", headers=headers)
        assert_eq(len(r.json()), 1, "list shows 1 after delete")

        # Delete non-existent
        r = await c.delete("/api/ha/instances/999999", headers=headers)
        assert_eq(r.status_code, 404, "delete non-existent returns 404")

        # Access without auth
        r = await c.get("/api/ha/instances")
        assert_eq(r.status_code, 401, "list without token returns 401")

        r = await c.post(
            "/api/ha/register",
            json={"name": "X", "ha_token": "x"},
        )
        assert_eq(r.status_code, 401, "register HA without auth returns 401")

        # Empty name (validation)
        r = await c.post(
            "/api/ha/register",
            json={"name": "", "ha_token": "x"},
            headers=headers,
        )
        assert_eq(r.status_code, 422, "empty name returns 422")

        # Clean up
        await c.delete(f"/api/ha/instances/{instance_id}", headers=headers)


# ─────────────────────────────────────────────────────────────────────────────
# Test 3: HA-facing endpoints (cloud_token auth)

async def test_ha_endpoints() -> None:
    print("\n[3/5] HA-facing endpoints (cloud_token auth)")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10) as c:
        access_token, _ = await setup_user(c, "haep")
        headers = {"Authorization": f"Bearer {access_token}"}
        instance = await setup_ha_instance(c, access_token, "HA Endpoints Test")
        cloud_token = instance["cloud_token"]
        instance_id = instance["id"]

        # Heartbeat with valid token
        r = await c.post(f"/api/ha/{cloud_token}/heartbeat", json={})
        assert_eq(r.status_code, 204, "heartbeat returns 204")

        # Verify is_online now (via WS) - actually heartbeat only updates last_seen
        # WS sets is_online. So we check last_seen via list.
        r = await c.get("/api/ha/instances", headers=headers)
        my_instance = [i for i in r.json() if i["id"] == instance_id][0]
        assert_eq(my_instance["last_seen"] is not None, True, "last_seen set after heartbeat")

        # Heartbeat with invalid token
        r = await c.post("/api/ha/invalid_xxx_token/heartbeat", json={})
        assert_eq(r.status_code, 404, "heartbeat with invalid token returns 404")

        # Sync devices
        devices = [
            {"entity_id": "light.living_room", "domain": "light", "name": "Living Room",
             "state": "off", "attributes": {"brightness": 0, "supported_color_modes": ["brightness"]}},
            {"entity_id": "switch.kitchen", "domain": "switch", "name": "Kitchen",
             "state": "on", "attributes": {}},
            {"entity_id": "sensor.temp_1", "domain": "sensor", "name": "Temperature",
             "state": "22.5", "attributes": {"unit_of_measurement": "°C"}},
        ]
        r = await c.post(f"/api/ha/{cloud_token}/devices/sync", json={"devices": devices})
        assert_eq(r.status_code, 200, "sync returns 200")
        sync_result = r.json()
        assert_eq(sync_result["count"], 3, "sync reports count=3")

        # User can see devices
        r = await c.get("/api/devices", headers=headers)
        assert_eq(r.status_code, 200, "list devices returns 200")
        device_list = r.json()
        assert_eq(len(device_list), 3, "user sees 3 devices")

        # Sync again with one device removed (test deletion)
        devices_minus_one = devices[:2]  # remove sensor
        r = await c.post(f"/api/ha/{cloud_token}/devices/sync", json={"devices": devices_minus_one})
        assert_eq(r.status_code, 200, "second sync returns 200")

        r = await c.get("/api/devices", headers=headers)
        assert_eq(len(r.json()), 2, "device count = 2 after removing one")

        # Sync with one device renamed (test update)
        updated = [
            {**devices[0], "name": "Living Room (renamed)", "state": "on",
             "attributes": {"brightness": 200}},
            devices[1],
        ]
        await c.post(f"/api/ha/{cloud_token}/devices/sync", json={"devices": updated})
        r = await c.get("/api/devices/light.living_room", headers=headers)
        device = r.json()
        assert_eq(device["name"], "Living Room (renamed)", "device name updated")
        assert_eq(device["state"], "on", "device state updated after sync")

        # Single state report
        r = await c.post(f"/api/ha/{cloud_token}/state", json={
            "entity_id": "switch.kitchen",
            "state": "off",
            "attributes": {"new_attr": "value"},
        })
        assert_eq(r.status_code, 200, "state report returns 200")

        r = await c.get("/api/devices/switch.kitchen", headers=headers)
        assert_eq(r.json()["state"], "off", "device state updated via state report")

        # State report for unknown device (auto-creates it)
        r = await c.post(f"/api/ha/{cloud_token}/state", json={
            "entity_id": "light.new_one",
            "state": "on",
            "attributes": {},
        })
        assert_eq(r.status_code, 200, "state report for new device returns 200")

        r = await c.get("/api/devices/light.new_one", headers=headers)
        assert_eq(r.status_code, 200, "auto-created device is accessible")

        # Sync with invalid token
        r = await c.post("/api/ha/invalid_xxx/devices/sync", json={"devices": []})
        assert_eq(r.status_code, 404, "sync with invalid token returns 404")

        # Get non-existent device
        r = await c.get("/api/devices/nonexistent.device", headers=headers)
        assert_eq(r.status_code, 404, "non-existent device returns 404")

        # Multi-user test: another user shouldn't see our devices
        other_token, _ = await setup_user(c, "haep-other")
        other_headers = {"Authorization": f"Bearer {other_token}"}
        r = await c.get("/api/devices", headers=other_headers)
        assert_eq(len(r.json()), 0, "other user sees 0 devices (isolation)")

        r = await c.get("/api/devices/light.living_room", headers=other_headers)
        assert_eq(r.status_code, 404, "other user gets 404 for our device")


# ─────────────────────────────────────────────────────────────────────────────
# Test 4: Device control

async def test_device_control() -> None:
    print("\n[4/5] Device control")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10) as c:
        access_token, _ = await setup_user(c, "ctrl")
        headers = {"Authorization": f"Bearer {access_token}"}
        instance = await setup_ha_instance(c, access_token, "Control Test")
        cloud_token = instance["cloud_token"]

        # Sync a device
        await c.post(
            f"/api/ha/{cloud_token}/devices/sync",
            json={"devices": [
                {"entity_id": "light.test", "domain": "light", "name": "Test Light",
                 "state": "off", "attributes": {"brightness": 0}}
            ]},
        )

        # Control when HA offline - expect 503
        r = await c.post(
            "/api/devices/light.test/action",
            json={"domain": "light", "service": "turn_on", "data": {"brightness": 200}},
            headers=headers,
        )
        assert_eq(r.status_code, 503, "control returns 503 when HA offline")

        # Non-existent device
        r = await c.post(
            "/api/devices/nonexistent/action",
            json={"domain": "light", "service": "turn_on", "data": {}},
            headers=headers,
        )
        assert_eq(r.status_code, 404, "control non-existent device returns 404")

        # Domain mismatch
        r = await c.post(
            "/api/devices/light.test/action",
            json={"domain": "switch", "service": "turn_on", "data": {}},
            headers=headers,
        )
        assert_eq(r.status_code, 400, "domain mismatch returns 400")

        # Without auth
        r = await c.post(
            "/api/devices/light.test/action",
            json={"domain": "light", "service": "turn_on", "data": {}},
        )
        assert_eq(r.status_code, 401, "control without auth returns 401")


# ─────────────────────────────────────────────────────────────────────────────
# Test 5: WebSocket

async def test_websocket() -> None:
    print("\n[5/5] WebSocket")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10) as c:
        access_token, _ = await setup_user(c, "ws")
        headers = {"Authorization": f"Bearer {access_token}"}
        instance = await setup_ha_instance(c, access_token, "WS Test")
        cloud_token = instance["cloud_token"]
        instance_id = instance["id"]

    # Test HA WS connection
    ws_url = f"{WS_URL}/ws/ha?token={cloud_token}"
    try:
        async with websockets.connect(ws_url, open_timeout=5) as ws:
            await ws.send("ping")
            resp = await asyncio.wait_for(ws.recv(), timeout=5)
            assert_eq(resp, "pong", "HA WS ping/pong")

            # HA should now be marked online
            await asyncio.sleep(0.5)
            async with httpx.AsyncClient(base_url=BASE_URL, timeout=5) as c2:
                r = await c2.get("/api/ha/instances", headers=headers)
                inst = [i for i in r.json() if i["id"] == instance_id][0]
                assert_eq(inst["is_online"], True, "HA marked online after WS connect")
        log_pass("HA WS connect/disconnect cleanly")

        # HA should be marked offline after disconnect
        await asyncio.sleep(1)
        async with httpx.AsyncClient(base_url=BASE_URL, timeout=5) as c2:
            r = await c2.get("/api/ha/instances", headers=headers)
            inst = [i for i in r.json() if i["id"] == instance_id][0]
            assert_eq(inst["is_online"], False, "HA marked offline after WS disconnect")
    except Exception as e:
        log_fail("HA WS connection", str(e))

    # Invalid HA token - server should reject handshake
    bad_url = f"{WS_URL}/ws/ha?token=invalid_xxx"
    try:
        async with websockets.connect(bad_url, open_timeout=5) as ws:
            try:
                await ws.recv()
                log_fail("Invalid HA token", "connection not rejected")
            except websockets.ConnectionClosed:
                log_pass("Invalid HA token rejected via close")
    except websockets.InvalidStatus as e:
        log_pass(f"Invalid HA token rejected at handshake ({e.response.status_code})")
    except Exception as e:
        log_fail("Invalid HA token", str(e))

    # Valid Client WS
    client_ws_url = f"{WS_URL}/ws/client?token={access_token}"
    try:
        async with websockets.connect(client_ws_url, open_timeout=5) as ws:
            await ws.send("ping")
            resp = await asyncio.wait_for(ws.recv(), timeout=5)
            assert_eq(resp, "pong", "Client WS ping/pong")
    except Exception as e:
        log_fail("Client WS", str(e))

    # Invalid Client token
    bad_client_url = f"{WS_URL}/ws/client?token=invalid"
    try:
        async with websockets.connect(bad_client_url, open_timeout=5) as ws:
            try:
                await ws.recv()
                log_fail("Invalid client token", "connection not rejected")
            except websockets.ConnectionClosed:
                log_pass("Invalid client token rejected via close")
    except websockets.InvalidStatus as e:
        log_pass(f"Invalid client token rejected at handshake ({e.response.status_code})")
    except Exception as e:
        log_fail("Invalid client token", str(e))


# ─────────────────────────────────────────────────────────────────────────────
# Main

async def main() -> int:
    print(f"Testing API at {BASE_URL}")
    print(f"Started at {time.strftime('%Y-%m-%d %H:%M:%S')}")

    # Connectivity check - exit early if backend unreachable
    try:
        async with httpx.AsyncClient(base_url=BASE_URL, timeout=5) as c:
            r = await c.get("/health")
            if r.status_code != 200:
                print(f"\n\033[31m✗\033[0m Health check failed: HTTP {r.status_code}")
                print(f"   Make sure {BASE_URL} is the correct address and the backend is running.")
                return 1
            log_pass("Health endpoint reachable")
    except httpx.ConnectError as e:
        print(f"\n\033[31m✗\033[0m Cannot connect to {BASE_URL}: {e}")
        print("   Make sure the backend is deployed and accessible.")
        return 1
    except Exception as e:
        print(f"\n\033[31m✗\033[0m Cannot reach {BASE_URL}: {e}")
        print("   Make sure the backend is deployed and accessible.")
        return 1

    await test_auth()
    await test_ha_instance()
    await test_ha_endpoints()
    await test_device_control()
    await test_websocket()

    print(f"\n{'='*60}")
    total = PASS + FAIL
    color = "\033[32m" if FAIL == 0 else "\033[31m"
    print(f"{color}Results: {PASS}/{total} passed, {FAIL} failed\033[0m")
    print("="*60)

    if ERRORS:
        print("\nFailures:")
        for name, msg in ERRORS:
            print(f"  - {name}: {msg}")

    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
