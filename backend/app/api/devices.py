"""Devices endpoint - returns devices grouped by area and device."""
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db
from app.deps import get_current_user
from app.models.device import HADevice, DeviceEntity
from app.models.ha_instance import HAInstance
from app.models.user import User
from app.schemas.device import DeviceActionRequest, HADeviceSchema
from app.ws.manager import ws_manager

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("", response_model=list[HADeviceSchema])
async def list_devices(
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """List all HA devices (with their entities) owned by the current user.

    Devices are grouped by HA's device registry, with all entities of the
    same device bundled together. Devices without a HA device_id (legacy
    or standalone entities) appear as one-entity devices.
    """
    stmt = (
        select(HADevice)
        .join(HAInstance, HADevice.ha_instance_id == HAInstance.id)
        .where(HAInstance.user_id == user.id)
        .options(selectinload(HADevice.entities))
        .order_by(HADevice.area.is_(None), HADevice.area, HADevice.name)
    )
    result = await db.execute(stmt)
    devices = result.scalars().all()

    # Also include "orphan" entities (no device_id) for backwards compat
    orphan_stmt = (
        select(DeviceEntity)
        .join(HAInstance, DeviceEntity.ha_instance_id == HAInstance.id)
        .where(
            HAInstance.user_id == user.id,
            DeviceEntity.ha_device_pk.is_(None),
        )
    )
    orphan_result = await db.execute(orphan_stmt)
    orphans = orphan_result.scalars().all()

    # Build response: real devices + synthetic devices for orphans.
    # Use plain Pydantic models for synthetics to avoid persisting them
    # (attribute assignment on ORM objects triggers session flush).
    from app.schemas.device import HADeviceSchema, DeviceEntitySchema
    response = []
    for d in devices:
        response.append(d)
    for e in orphans:
        synthetic = HADeviceSchema(
            id=-e.id,  # negative to avoid collision with real ids
            ha_device_id=f"orphan-{e.entity_id}",
            name=e.name or e.entity_id,
            entities=[DeviceEntitySchema.model_validate(e)],
        )
        response.append(synthetic)
    return response


@router.get("/{entity_id:path}", response_model=HADeviceSchema)
async def get_device(
    entity_id: str,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Get a single device by entity_id (returns the parent device)."""
    stmt = (
        select(DeviceEntity)
        .join(HAInstance, DeviceEntity.ha_instance_id == HAInstance.id)
        .where(HAInstance.user_id == user.id, DeviceEntity.entity_id == entity_id)
        .options(selectinload(DeviceEntity.device))
    )
    ent = (await db.execute(stmt)).scalar_one_or_none()
    if not ent:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    if ent.device:
        return ent.device
    # Orphan entity - wrap as a plain Pydantic model so it doesn't get
    # persisted to the DB (ORM back-populates would otherwise commit).
    from app.schemas.device import HADeviceSchema, DeviceEntitySchema
    return HADeviceSchema(
        id=-ent.id,
        ha_device_id=f"orphan-{ent.entity_id}",
        name=ent.name or ent.entity_id,
        entities=[DeviceEntitySchema.model_validate(ent)],
    )


@router.post("/{entity_id:path}/action")
async def control_device(
    entity_id: str,
    body: DeviceActionRequest,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Send a service call to an entity via its HA instance.

    body.domain must match the entity's domain.
    """
    stmt = (
        select(DeviceEntity, HAInstance)
        .join(HAInstance, DeviceEntity.ha_instance_id == HAInstance.id)
        .where(HAInstance.user_id == user.id, DeviceEntity.entity_id == entity_id)
    )
    row = (await db.execute(stmt)).first()
    if not row:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    device, instance = row

    if body.domain and body.domain != device.domain:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            f"Domain mismatch: device is {device.domain}, request says {body.domain}",
        )

    delivered = await ws_manager.send_to_ha(
        instance.cloud_token,
        {
            "type": "device_action",
            "entity_id": entity_id,
            "domain": device.domain,
            "service": body.service,
            "data": body.data,
        },
    )
    if not delivered:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "HA instance is offline",
        )
    return {"status": "sent", "entity_id": entity_id}
