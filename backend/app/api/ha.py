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
    body: dict,
    db,
):
    """HA instance sends full state.

    New schema:
        body = {
            "devices": {ha_device_id: {name, manufacturer, model, area, ...}, ...},
            "entities": [{entity_id, domain, name, state, attributes, ha_device_id}, ...],
        }

    Backwards compat: if body has only "devices" list (old format), wrap
    each as its own single-entity synthetic device.
    """
    from app.models.device import HADevice, DeviceEntity

    instance = await _get_instance_by_token(cloud_token, db)

    raw = body or {}
    if "entities" in raw and isinstance(raw.get("entities"), list):
        devices_dict = raw.get("devices") or {}
        entities_list = raw["entities"]
    elif "devices" in raw and isinstance(raw["devices"], list):
        devices_dict = {}
        entities_list = []
        for item in raw["devices"]:
            eid = item.get("entity_id", f"unknown-{len(entities_list)}")
            fake_did = f"legacy-{eid}"
            devices_dict[fake_did] = {"name": item.get("name", eid)}
            entities_list.append({**item, "ha_device_id": fake_did})
    else:
        devices_dict = {}
        entities_list = []

    existing_devs = await db.execute(
        select(HADevice).where(HADevice.ha_instance_id == instance.id)
    )
    existing_devs_map = {d.ha_device_id: d for d in existing_devs.scalars().all()}
    devs_added = devs_updated = 0
    for ha_device_id, d in devices_dict.items():
        existing = existing_devs_map.get(ha_device_id)
        if existing:
            existing.name = d.get("name", existing.name)
            existing.manufacturer = d.get("manufacturer")
            existing.model = d.get("model")
            existing.area = d.get("area")
            existing.sw_version = d.get("sw_version")
            existing.hw_version = d.get("hw_version")
            devs_updated += 1
        else:
            db.add(HADevice(
                ha_instance_id=instance.id,
                ha_device_id=ha_device_id,
                name=d.get("name", "Unknown"),
                manufacturer=d.get("manufacturer"),
                model=d.get("model"),
                area=d.get("area"),
                sw_version=d.get("sw_version"),
                hw_version=d.get("hw_version"),
            ))
            devs_added += 1

    await db.flush()
    existing_devs = await db.execute(
        select(HADevice).where(HADevice.ha_instance_id == instance.id)
    )
    existing_devs_map = {d.ha_device_id: d for d in existing_devs.scalars().all()}

    existing_ents = await db.execute(
        select(DeviceEntity).where(DeviceEntity.ha_instance_id == instance.id)
    )
    existing_ents_map = {e.entity_id: e for e in existing_ents.scalars().all()}
    incoming_entity_ids = set()
    ents_added = ents_updated = 0
    for ent in entities_list:
        eid = ent.get("entity_id")
        if not eid:
            continue
        incoming_entity_ids.add(eid)
        ha_dev_id = ent.get("ha_device_id")
        dev_pk = None
        if ha_dev_id:
            d_obj = existing_devs_map.get(ha_dev_id)
            if d_obj:
                dev_pk = d_obj.id
        existing = existing_ents_map.get(eid)
        if existing:
            existing.domain = ent.get("domain", existing.domain)
            existing.name = ent.get("name", existing.name)
            existing.state = ent.get("state", existing.state)
            existing.attributes = ent.get("attributes", existing.attributes)
            existing.ha_device_pk = dev_pk
            existing.entity_category = ent.get("entity_category")
            ents_updated += 1
        else:
            db.add(DeviceEntity(
                ha_instance_id=instance.id,
                ha_device_pk=dev_pk,
                entity_id=eid,
                domain=ent.get("domain", ""),
                name=ent.get("name", ""),
                state=ent.get("state", "unknown"),
                attributes=ent.get("attributes", {}),
                entity_category=ent.get("entity_category"),
            ))
            ents_added += 1

    deleted = 0
    for eid, e in list(existing_ents_map.items()):
        if eid not in incoming_entity_ids:
            await db.delete(e)
            deleted += 1

    await db.commit()

    await ws_manager.broadcast_to_user(
        instance.user_id,
        {"type": "devices_synced",
         "devices": devs_added + devs_updated,
         "entities": ents_added + ents_updated},
    )
    return {
        "status": "ok",
        "devices_added": devs_added,
        "devices_updated": devs_updated,
        "entities_added": ents_added,
        "entities_updated": ents_updated,
        "entities_deleted": deleted,
    }

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
