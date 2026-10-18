"""Devices endpoint - returns devices grouped by area and device."""
import asyncio
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
from app.schemas.device import DeviceActionRequest, DeviceStateSchema, HADeviceSchema
from app.ws.manager import ws_manager

router = APIRouter(prefix="/devices", tags=["devices"])


def _error(http_status: int, error_type: str, message: str, entity_id: str) -> HTTPException:
    """Build a structured-detail HTTPException used by every 404/400
    path under this router.

    All clients should read `detail["error_type"]` first and only fall
    back to `detail["message"]` substring matching when error_type is
    missing (legacy code paths). The `message` text is preserved
    byte-for-byte from the old bare-string variants so the
    device-installed Android capability's `detail.contains(...)`
    fallback path keeps working — do not reword any of these strings
    without first updating that APK.
    """
    return HTTPException(
        http_status,
        {"error_type": error_type, "message": message, "entity_id": entity_id},
    )


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


@router.get("/{entity_id:path}/state", response_model=DeviceStateSchema)
async def get_device_state(
    entity_id: str,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Get a single entity's flat state (no parent device wrapping).

    Declared before the `/{entity_id:path}` catch-all so the literal
    suffix "/state" is matched by this route, not absorbed by get_device.
    """
    stmt = (
        select(DeviceEntity)
        .join(HAInstance, DeviceEntity.ha_instance_id == HAInstance.id)
        .where(
            HAInstance.user_id == user.id,
            DeviceEntity.entity_id == entity_id,
        )
    )
    ent = (await db.execute(stmt)).scalar_one_or_none()
    if not ent:
        raise _error(status.HTTP_404_NOT_FOUND, "entity_not_found", "Entity not found", entity_id)
    return ent


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
        # Preload DeviceEntity → its parent HADevice → its entities,
        # because FastAPI serializes HADeviceSchema AFTER this handler
        # returns. Without the second selectinload, accessing
        # .entities on a "real" device triggers async lazy-load
        # outside a greenlet and raises MissingGreenlet (HTTP 500).
        .options(
            selectinload(DeviceEntity.device)
            .selectinload(HADevice.entities)
        )
    )
    ent = (await db.execute(stmt)).scalar_one_or_none()
    if not ent:
        raise _error(status.HTTP_404_NOT_FOUND, "device_not_found", "Entity not found", entity_id)
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
        raise _error(status.HTTP_404_NOT_FOUND, "device_not_found", "Entity not found", entity_id)
    device, instance = row

    if body.domain and body.domain != device.domain:
        # Message text MUST start with "Domain mismatch: device is " —
        # the device-installed Android capability falls back to
        # `detail.contains("Domain mismatch")` when error_type is
        # missing on legacy responses, so don't reword this.
        raise _error(
            status.HTTP_400_BAD_REQUEST,
            "domain_mismatch",
            f"Domain mismatch: device is {device.domain}, request says {body.domain}",
            entity_id,
        )

    # Send the action and block until the HA plugin reports the new
    # state. The plugin runs the service call with blocking=True and
    # then sends back a `device_action_result` carrying the
    # post-action state, which is what we return.
    #
    # Without this, the response was {"status": "sent"} and clients
    # polling /state immediately after the action would read the
    # pre-action value (DB not yet updated by the plugin's
    # state_change broadcast). Now the response IS the new state.
    import uuid
    request_id = uuid.uuid4().hex
    try:
        result = await ws_manager.request_action_and_wait(
            instance.cloud_token,
            {
                "type": "device_action",
                "request_id": request_id,
                "entity_id": entity_id,
                "domain": device.domain,
                "service": body.service,
                "data": body.data,
            },
            timeout=5.0,
        )
    except ConnectionError:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            {"error_type": "ha_offline", "message": "HA instance is offline"},
        )
    except asyncio.TimeoutError:
        # Plugin didn't reply in 5s. The action may or may not have
        # landed on the device; we just can't tell. Surface as 504 so
        # clients can decide whether to retry.
        raise HTTPException(
            status.HTTP_504_GATEWAY_TIMEOUT,
            {
                "error_type": "ha_timeout",
                "message": "HA did not report action result in time",
            },
        )

    if not result.get("ok", False):
        # The plugin's service call itself failed. Map by error_type
        # so the client gets a proper caller error (400) vs gateway
        # error (502). error_type is mandatory-ish in new plugins; old
        # plugins that don't send it default to "call_failed".
        error_type = result.get("error_type", "call_failed")
        err = result.get("error", "HA service call failed")
        if error_type == "service_not_found":
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST,
                {
                    "error_type": "service_not_found",
                    "message": err,
                    "entity_id": entity_id,
                },
            )
        if error_type == "invalid_params":
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST,
                {
                    "error_type": "invalid_params",
                    "message": err,
                    "entity_id": entity_id,
                },
            )
        # default: call_failed or missing error_type → 502
        raise HTTPException(
            status.HTTP_502_BAD_GATEWAY,
            {
                "error_type": "call_failed",
                "message": err,
                "entity_id": entity_id,
            },
        )

    return {
        "status": "ok",
        "entity_id": entity_id,
        "state": result.get("state"),
        "attributes": result.get("attributes", {}),
        "last_state_change": result.get("last_state_change"),
    }
