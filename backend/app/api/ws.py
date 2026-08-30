"""WebSocket endpoints for HA instances and clients."""
import json
import logging
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from jose import JWTError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import decode_token
from app.database import AsyncSessionLocal
from app.models.ha_instance import HAInstance
from app.models.device import Device
from app.models.user import User
from app.schemas.ha import DeviceSyncItem
from app.ws.manager import ws_manager
from pydantic import ValidationError

logger = logging.getLogger(__name__)

router = APIRouter()


async def _handle_device_sync(instance_id: int, raw_msg: dict) -> int:
    """Process a device_sync WS message. Returns number of devices synced."""
    try:
        devices_data = raw_msg.get("devices", [])
    except Exception as e:
        logger.warning("device_sync: invalid payload: %s", e)
        return 0
    if not isinstance(devices_data, list):
        return 0
    
    async with AsyncSessionLocal() as db:
        existing_res = await db.execute(
            select(Device).where(Device.ha_instance_id == instance_id)
        )
        existing_by_entity = {d.entity_id: d for d in existing_res.scalars().all()}
        incoming_entity_ids = set()
        
        added = updated = 0
        for item_dict in devices_data:
            try:
                item = DeviceSyncItem(**item_dict)
            except ValidationError as e:
                logger.warning("device_sync: invalid item: %s", e)
                continue
            incoming_entity_ids.add(item.entity_id)
            device = existing_by_entity.get(item.entity_id)
            if device:
                device.domain = item.domain
                device.name = item.name
                device.state = item.state
                device.attributes = item.attributes
                device.last_state_change = datetime.now(timezone.utc)
                updated += 1
            else:
                db.add(Device(
                    ha_instance_id=instance_id,
                    entity_id=item.entity_id,
                    domain=item.domain,
                    name=item.name,
                    state=item.state,
                    attributes=item.attributes,
                ))
                added += 1
        
        # Delete devices no longer present
        deleted = 0
        for entity_id, device in list(existing_by_entity.items()):
            if entity_id not in incoming_entity_ids:
                await db.delete(device)
                deleted += 1
        
        await db.commit()
        logger.info(
            "device_sync for instance %d: +%d ~%d -%d (total incoming: %d)",
            instance_id, added, updated, deleted, len(devices_data),
        )
        return added + updated


async def _handle_state_change(instance_id: int, raw_msg: dict) -> bool:
    """Process a state_change WS message. Returns True if updated."""
    entity_id = raw_msg.get("entity_id")
    if not entity_id:
        return False
    state = raw_msg.get("state")
    attributes = raw_msg.get("attributes", {})
    
    async with AsyncSessionLocal() as db:
        device = await db.scalar(
            select(Device).where(
                Device.ha_instance_id == instance_id,
                Device.entity_id == entity_id,
            )
        )
        if not device:
            # Auto-create (state_change may arrive before initial sync completes)
            domain = entity_id.split(".", 1)[0] if "." in entity_id else "unknown"
            db.add(Device(
                ha_instance_id=instance_id,
                entity_id=entity_id,
                domain=domain,
                name=entity_id,
                state=state or "unknown",
                attributes=attributes,
            ))
            await db.commit()
            return True
        device.state = state
        device.attributes = attributes
        device.last_state_change = datetime.now(timezone.utc)
        await db.commit()
        return True


@router.websocket("/ws/ha")
async def websocket_ha(websocket: WebSocket):
    """WebSocket endpoint for Home Assistant instances."""
    cloud_token = websocket.query_params.get("token")
    if not cloud_token:
        await websocket.close(code=1008, reason="Missing token")
        return
    
    async with AsyncSessionLocal() as db:
        instance = await db.scalar(
            select(HAInstance).where(HAInstance.cloud_token == cloud_token)
        )
        if not instance:
            await websocket.close(code=1008, reason="Invalid token")
            return
        instance_id = instance.id
        instance.is_online = True
        await db.commit()
        user_id = instance.user_id
    
    await websocket.accept()
    await ws_manager.connect_ha(cloud_token, websocket)
    await ws_manager.broadcast_to_user(user_id, {"type": "ha_online", "ha_instance_id": instance_id})
    
    try:
        while True:
            raw = await websocket.receive_text()
            if raw == "ping":
                await websocket.send_text("pong")
                continue
            # Parse JSON
            try:
                msg = json.loads(raw)
            except (json.JSONDecodeError, ValueError):
                continue
            mtype = msg.get("type")
            if mtype == "ping":
                await websocket.send_json({"type": "pong"})
            elif mtype == "device_sync":
                count = await _handle_device_sync(instance_id, msg)
                try:
                    await websocket.send_json({"type": "sync_ack", "count": count})
                except Exception:
                    pass
            elif mtype == "state_change":
                await _handle_state_change(instance_id, msg)
            else:
                logger.debug("WS HA: unknown msg type %s", mtype)
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.exception("HA WS error: %s", e)
    finally:
        await ws_manager.disconnect_ha(cloud_token)
        async with AsyncSessionLocal() as db:
            inst = await db.scalar(
                select(HAInstance).where(HAInstance.cloud_token == cloud_token)
            )
            if inst:
                inst.is_online = False
                await db.commit()
                await ws_manager.broadcast_to_user(user_id, {"type": "ha_offline", "ha_instance_id": inst.id})


@router.websocket("/ws/client")
async def websocket_client(websocket: WebSocket):
    """WebSocket endpoint for mobile app clients."""
    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(code=1008, reason="Missing token")
        return
    
    try:
        payload = decode_token(token)
        if payload.get("type") != "access":
            await websocket.close(code=1008, reason="Invalid token type")
            return
        user_id = int(payload["sub"])
    except (JWTError, KeyError, ValueError):
        await websocket.close(code=1008, reason="Invalid token")
        return
    
    async with AsyncSessionLocal() as db:
        user = await db.get(User, user_id)
        if not user:
            await websocket.close(code=1008, reason="User not found")
            return
    
    await websocket.accept()
    await ws_manager.connect_client(user_id, websocket)
    
    try:
        while True:
            msg = await websocket.receive_text()
            if msg == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.exception("Client WS error: %s", e)
    finally:
        await ws_manager.disconnect_client(user_id, websocket)
