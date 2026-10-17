"""Test fixtures.

We use SQLite in-memory for unit tests so they run anywhere without a
running Postgres. The default `app.database.engine` is created at import
time with a Postgres URL (so it loads cleanly); tests then create their
own SQLite engine and override the FastAPI `get_db` dependency.
"""
import asyncio
import os
from typing import AsyncGenerator

# Force a Postgres URL before any `app.*` import so the module-level
# engine in app.database.py doesn't fail on SQLite-incompatible
# pool_size/max_overflow kwargs.
os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://cloudlink:cloudlink@localhost:5432/cloudlink",
)

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.database import Base, get_db
from app.main import app


SQLITE_TEST_URL = "sqlite+aiosqlite:///:memory:"


def _patch_server_defaults_for_sqlite() -> None:
    """SQLite doesn't accept the literal string "now()" as a server
    default (it's only a Postgres shorthand). Rewrite all such
    server_defaults to a DefaultClause wrapping text("CURRENT_TIMESTAMP")
    so the SQLAlchemy ORM can bool-check it. Works on Postgres and SQLite."""
    from sqlalchemy.schema import DefaultGenerator
    from sqlalchemy.sql import text
    from sqlalchemy.sql.schema import DefaultClause

    for table in Base.metadata.tables.values():
        for col in table.columns:
            sd = col.server_default
            if sd is None:
                continue
            arg = getattr(sd, "arg", None)
            if isinstance(arg, str) and arg.lower().strip("()") in ("now", "current_timestamp"):
                col.server_default = DefaultClause(text("CURRENT_TIMESTAMP"), for_update=False)


_patch_server_defaults_for_sqlite()


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture
async def db_session() -> AsyncGenerator[AsyncSession, None]:
    """Per-test SQLite-in-memory session with a fresh schema."""
    engine = create_async_engine(
        SQLITE_TEST_URL,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
        echo=False,
    )
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    Session = async_sessionmaker(engine, expire_on_commit=False)
    async with Session() as session:
        yield session

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest_asyncio.fixture
async def client(db_session: AsyncSession) -> AsyncGenerator[AsyncClient, None]:
    """Test HTTP client with DB dependency override."""
    async def override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def auth_token(client: AsyncClient) -> str:
    """Register + login a fresh user, return access_token."""
    await client.post("/api/auth/register", json={
        "email": "tester@example.com",
        "password": "password123",
    })
    resp = await client.post("/api/auth/login", json={
        "email": "tester@example.com",
        "password": "password123",
    })
    return resp.json()["access_token"]


@pytest_asyncio.fixture
async def user_with_instance(db_session: AsyncSession):
    """Insert a User + HAInstance + a couple of entities; return the
    user object (already committed and refreshed)."""
    from app.models.ha_instance import HAInstance
    from app.models.user import User
    from app.models.device import DeviceEntity, HADevice
    from datetime import datetime, timezone

    from app.core.security import hash_password
    user = User(email="ha-owner@example.com", password_hash=hash_password("password123"))
    db_session.add(user)
    await db_session.flush()

    instance = HAInstance(
        user_id=user.id,
        name="我的家",
        cloud_token="cloud-tok-abc",
        encrypted_ha_token=b"\x00" * 32,  # dummy bytes; tests don't decrypt
    )
    db_session.add(instance)
    await db_session.flush()

    dev = HADevice(
        ha_instance_id=instance.id,
        ha_device_id="dev-001",
        name="Yeelight Lamp",
        manufacturer="Xiaomi",
        model="yeelink.light.mb1",
        area="Living Room",
    )
    db_session.add(dev)
    await db_session.flush()

    light = DeviceEntity(
        ha_instance_id=instance.id,
        ha_device_pk=dev.id,
        entity_id="light.living_room",
        domain="light",
        name="Living Room Light",
        state="on",
        attributes={"brightness": 200, "color_mode": "xy"},
        last_state_change=datetime(2026, 10, 17, 10, 0, tzinfo=timezone.utc),
    )
    db_session.add(light)

    sensor = DeviceEntity(
        ha_instance_id=instance.id,
        ha_device_pk=dev.id,
        entity_id="sensor.living_room_temp",
        domain="sensor",
        name="Living Room Temperature",
        state="21.5",
        attributes={"unit_of_measurement": "°C"},
    )
    db_session.add(sensor)

    # Orphan entity (no device)
    orphan = DeviceEntity(
        ha_instance_id=instance.id,
        entity_id="switch.kitchen_fan",
        domain="switch",
        name="Kitchen Fan",
        state="off",
        attributes={},
    )
    db_session.add(orphan)

    await db_session.commit()
    for obj in (user, instance, dev, light, sensor, orphan):
        await db_session.refresh(obj)
    return user
