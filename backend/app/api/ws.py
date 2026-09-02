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
from app.models.device import DeviceEntity, HADevice
from app.models.user import User
from app.schemas.ha import DeviceSyncItem
from app.ws.manager import ws_manager
from pydantic import ValidationError

logger = logging.getLogger(__name__)

router = APIRouter()


async def _handle_device_sync(instance_id: int, raw_msg: dict) -> int:
    """Process a device_sync WS message.

    Supports two formats:
      New: {"devices": {ha_device_id: {name, manufacturer, ...}},
            "entities": [{entity_id, domain, ..., ha_device_id, entity_category}]}
      Old (backwards compat): {"devices": [{entity_id, domain, name, state, ...}]}
    """
    # Detect format and normalise into (devices_dict, entities_list)
    if "entities" in raw_msg and isinstance(raw_msg.get("entities"), list):
        # New format
        devices_dict = raw_msg.get("devices") or {}
        entities_list = raw_msg["entities"]
    elif "devices" in raw_msg and isinstance(raw_msg["devices"], list):
        # Old format: wrap each as its own synthetic device
        devices_dict = {}
        entities_list = []
        for item in raw_msg["devices"]:
            eid = item.get("entity_id", f"unknown-{len(entities_list)}")
            fake_did = f"legacy-{eid}"
            devices_dict[fake_did] = {"name": item.get("name", eid)}
            entities_list.append({**item, "ha_device_id": fake_did})
    else:
        logger.warning("device_sync: unrecognised payload shape")
        return 0

    async with AsyncSessionLocal() as db:
        # 1. Upsert HADevice rows
        existing_devs = await db.execute(
            select(HADevice).where(HADevice.ha_instance_id == instance_id)
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
                    ha_instance_id=instance_id,
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

        # 2. Upsert DeviceEntity rows
        existing_devs = await db.execute(
            select(HADevice).where(HADevice.ha_instance_id == instance_id)
        )
        existing_devs_map = {d.ha_device_id: d for d in existing_devs.scalars().all()}
        existing_ents = await db.execute(
            select(DeviceEntity).where(DeviceEntity.ha_instance_id == instance_id)
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
                    ha_instance_id=instance_id,
                    ha_device_pk=dev_pk,
                    entity_id=eid,
                    domain=ent.get("domain", ""),
                    name=ent.get("name", ""),
                    state=ent.get("state", "unknown"),
                    attributes=ent.get("attributes", {}),
                    entity_category=ent.get("entity_category"),
                ))
                ents_added += 1

        # 3. Delete entities not in incoming list (replace strategy)
        deleted = 0
        for eid, e in list(existing_ents_map.items()):
            if eid not in incoming_entity_ids:
                await db.delete(e)
                deleted += 1

        await db.commit()
        logger.info(
            "device_sync for instance %d: devices +%d ~%d, entities +%d ~%d -%d (incoming: %d)",
            instance_id, devs_added, devs_updated,
            ents_added, ents_updated, deleted, len(entities_list),
        )
        return ents_added + ents_updated


async def _handle_state_change(instance_id: int, raw_msg: dict, user_id: int) -> bool:
    """Process a state_change WS message. Returns True if updated.

    Also broadcasts the change to the user's mobile clients so their UI
    updates in real time.
    """
    entity_id = raw_msg.get("entity_id")
    if not entity_id:
        return False
    state = raw_msg.get("state")
    attributes = raw_msg.get("attributes", {})

    async with AsyncSessionLocal() as db:
        device = await db.scalar(
            select(DeviceEntity).where(
                DeviceEntity.ha_instance_id == instance_id,
                DeviceEntity.entity_id == entity_id,
            )
        )
        if not device:
            # Auto-create (state_change may arrive before initial sync completes)
            domain = entity_id.split(".", 1)[0] if "." in entity_id else "unknown"
            db.add(DeviceEntity(
                ha_instance_id=instance_id,
                entity_id=entity_id,
                domain=domain,
                name=entity_id,
                state=state or "unknown",
                attributes=attributes,
            ))
            await db.commit()
            user_msg = {
                "type": "state_change",
                "entity_id": entity_id,
                "state": state or "unknown",
                "attributes": attributes,
            }
        else:
            device.state = state
            device.attributes = attributes
            device.last_state_change = datetime.now(timezone.utc)
            await db.commit()
            user_msg = {
                "type": "state_change",
                "entity_id": entity_id,
                "state": state,
                "attributes": attributes,
            }

    # Broadcast to user's mobile clients so the UI updates in real time
    await ws_manager.broadcast_to_user(user_id, user_msg)
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
                await _handle_state_change(instance_id, msg, user_id)
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
