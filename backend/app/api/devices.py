"""Device listing and control endpoints (user-facing)."""
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db
from app.deps import get_current_user
from app.models.device import Device
from app.models.ha_instance import HAInstance
from app.models.user import User
from app.schemas.device import DeviceActionRequest, DeviceResponse
from app.ws.manager import ws_manager

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("", response_model=list[DeviceResponse])
async def list_devices(
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """List all devices across the user's HA instances."""
    stmt = (
        select(Device)
        .join(HAInstance, Device.ha_instance_id == HAInstance.id)
        .where(HAInstance.user_id == user.id)
        .options(selectinload(Device.ha_instance))
        .order_by(Device.domain, Device.name)
    )
    result = await db.execute(stmt)
    return result.scalars().all()


@router.get("/{entity_id:path}", response_model=DeviceResponse)
async def get_device(
    entity_id: str,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Get details of a single device."""
    stmt = (
        select(Device)
        .join(HAInstance, Device.ha_instance_id == HAInstance.id)
        .where(HAInstance.user_id == user.id, Device.entity_id == entity_id)
    )
    device = (await db.execute(stmt)).scalar_one_or_none()
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    return device


@router.post("/{entity_id:path}/action")
async def control_device(
    entity_id: str,
    body: DeviceActionRequest,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Send a service call to a device via its HA instance."""
    # Find the device and its HA instance
    stmt = (
        select(Device, HAInstance)
        .join(HAInstance, Device.ha_instance_id == HAInstance.id)
        .where(HAInstance.user_id == user.id, Device.entity_id == entity_id)
    )
    row = (await db.execute(stmt)).first()
    if not row:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    device, instance = row
    
    # Verify domain matches
    if body.domain and body.domain != device.domain:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            f"Domain mismatch: device is {device.domain}, request says {body.domain}",
        )
    
    # Send to HA via WebSocket
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
