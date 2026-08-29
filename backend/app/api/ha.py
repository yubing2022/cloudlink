"""Home Assistant management endpoints (user-facing + HA-facing)."""
from datetime import datetime, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.crypto import decrypt_ha_token, encrypt_ha_token
from app.database import get_db
from app.deps import get_current_user
from app.models.device import Device
from app.models.ha_instance import HAInstance
from app.models.user import User
from app.schemas.ha import (
    DeviceSyncItem, DeviceSyncRequest, HARegisterRequest, HARegisterResponse,
    HAInstanceResponse, HeartbeatRequest, StateReportRequest,
)
from app.ws.manager import ws_manager

# Two routers: one for user-facing (JWT auth), one for HA-facing (cloud_token auth)
user_router = APIRouter(prefix="/ha", tags=["ha"])
ha_router = APIRouter(prefix="/ha", tags=["ha-internal"])


# ============ User-facing endpoints (JWT auth) ============

@user_router.post("/register", response_model=HARegisterResponse, status_code=status.HTTP_201_CREATED)
async def register_ha(
    body: HARegisterRequest,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """User registers a new Home Assistant instance."""
    instance = HAInstance(
        user_id=user.id,
        name=body.name,
        encrypted_ha_token=encrypt_ha_token(body.ha_token),
    )
    db.add(instance)
    await db.commit()
    await db.refresh(instance)
    return instance


@user_router.get("/instances", response_model=list[HAInstanceResponse])
async def list_instances(
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """List all HA instances owned by the current user."""
    # Single query with subquery for device counts
    device_count = (
        select(Device.ha_instance_id, func.count(Device.id).label("cnt"))
        .group_by(Device.ha_instance_id)
        .subquery()
    )
    stmt = (
        select(HAInstance, device_count.c.cnt)
        .outerjoin(device_count, HAInstance.id == device_count.c.ha_instance_id)
        .where(HAInstance.user_id == user.id)
        .order_by(HAInstance.created_at.desc())
    )
    result = await db.execute(stmt)
    rows = result.all()
    return [
        HAInstanceResponse(
            id=inst.id,
            name=inst.name,
            is_online=inst.is_online,
            last_seen=inst.last_seen,
            device_count=cnt or 0,
            created_at=inst.created_at,
        )
        for inst, cnt in rows
    ]


@user_router.delete("/instances/{instance_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_instance(
    instance_id: int,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    instance = await db.get(HAInstance, instance_id)
    if not instance or instance.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Instance not found")
    await db.delete(instance)
    await db.commit()


# ============ HA-facing endpoints (cloud_token auth) ============

async def _get_instance_by_token(
    cloud_token: str, db: AsyncSession,
) -> HAInstance:
    instance = await db.scalar(
        select(HAInstance).where(HAInstance.cloud_token == cloud_token)
    )
    if not instance:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Invalid cloud_token")
    return instance


@ha_router.post("/{cloud_token}/heartbeat", status_code=status.HTTP_204_NO_CONTENT)
async def heartbeat(
    cloud_token: str,
    body: HeartbeatRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """HA instance sends periodic heartbeat."""
    instance = await _get_instance_by_token(cloud_token, db)
    instance.last_seen = body.timestamp or datetime.now(timezone.utc)
    instance.is_online = True
    await db.commit()


@ha_router.post("/{cloud_token}/devices/sync")
async def sync_devices(
    cloud_token: str,
    body: DeviceSyncRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """HA instance sends a full list of devices (replace strategy)."""
    instance = await _get_instance_by_token(cloud_token, db)
    
    # Fetch existing devices for this instance
    existing = await db.execute(
        select(Device).where(Device.ha_instance_id == instance.id)
    )
    existing_by_entity = {d.entity_id: d for d in existing.scalars().all()}
    incoming_entity_ids = {item.entity_id for item in body.devices}
    
    # Upsert each incoming device
    for item in body.devices:
        device = existing_by_entity.get(item.entity_id)
        if device:
            device.domain = item.domain
            device.name = item.name
            device.state = item.state
            device.attributes = item.attributes
        else:
            db.add(Device(
                ha_instance_id=instance.id,
                entity_id=item.entity_id,
                domain=item.domain,
                name=item.name,
                state=item.state,
                attributes=item.attributes,
            ))
    
    # Delete devices no longer present
    for entity_id, device in existing_by_entity.items():
        if entity_id not in incoming_entity_ids:
            await db.delete(device)
    
    await db.commit()
    
    # Notify the user's mobile clients
    await ws_manager.broadcast_to_user(
        instance.user_id,
        {"type": "devices_synced", "count": len(body.devices)},
    )
    return {"status": "ok", "count": len(body.devices)}


@ha_router.post("/{cloud_token}/state")
async def report_state(
    cloud_token: str,
    body: StateReportRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """HA instance reports a single device state change."""
    instance = await _get_instance_by_token(cloud_token, db)
    device = await db.scalar(
        select(Device).where(
            Device.ha_instance_id == instance.id,
            Device.entity_id == body.entity_id,
        )
    )
    if not device:
        device = Device(
            ha_instance_id=instance.id,
            entity_id=body.entity_id,
            domain=body.entity_id.split(".")[0] if "." in body.entity_id else "unknown",
            name=body.entity_id,
            state=body.state,
            attributes=body.attributes,
        )
        db.add(device)
    else:
        device.state = body.state
        device.attributes = body.attributes
    
    await db.commit()
    
    # Push to mobile clients
    await ws_manager.broadcast_to_user(
        instance.user_id,
        {
            "type": "state_change",
            "entity_id": body.entity_id,
            "state": body.state,
            "attributes": body.attributes,
        },
    )
    return {"status": "ok"}
