"""Tests for authentication endpoints."""
import pytest


@pytest.mark.asyncio
async def test_register_login_flow(client):
    # Register
    resp = await client.post("/api/auth/register", json={
        "email": "test@example.com",
        "password": "supersecret123",
    })
    assert resp.status_code == 201, resp.text
    tokens = resp.json()
    assert "access_token" in tokens
    assert "refresh_token" in tokens
    
    # Login
    resp = await client.post("/api/auth/login", json={
        "email": "test@example.com",
        "password": "supersecret123",
    })
    assert resp.status_code == 200
    tokens = resp.json()
    assert "access_token" in tokens
    
    # /me with token
    resp = await client.get("/api/auth/me", headers={
        "Authorization": f"Bearer {tokens['access_token']}",
    })
    assert resp.status_code == 200
    assert resp.json()["email"] == "test@example.com"


@pytest.mark.asyncio
async def test_register_duplicate_email(client):
    await client.post("/api/auth/register", json={
        "email": "dup@example.com",
        "password": "password123",
    })
    resp = await client.post("/api/auth/register", json={
        "email": "dup@example.com",
        "password": "password456",
    })
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_login_wrong_password(client):
    await client.post("/api/auth/register", json={
        "email": "wrong@example.com",
        "password": "rightpass1",
    })
    resp = await client.post("/api/auth/login", json={
        "email": "wrong@example.com",
        "password": "wrongpass1",
    })
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_refresh_token(client):
    resp = await client.post("/api/auth/register", json={
        "email": "refresh@example.com",
        "password": "password123",
    })
    refresh = resp.json()["refresh_token"]
    
    resp = await client.post("/api/auth/refresh", json={"refresh_token": refresh})
    assert resp.status_code == 200
    new_tokens = resp.json()
    assert new_tokens["access_token"] != refresh


@pytest.mark.asyncio
async def test_me_without_token(client):
    resp = await client.get("/api/auth/me")
    assert resp.status_code == 401
