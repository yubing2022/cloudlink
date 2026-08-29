"""Tests for device endpoints."""
import pytest


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
