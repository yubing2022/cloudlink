"""WebSocket endpoints."""
import json
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from jose import JWTError
from sqlalchemy import select

from app.core.security import decode_token
from app.database import AsyncSessionLocal
from app.models.ha_instance import HAInstance
from app.models.user import User
from app.ws.manager import ws_manager

logger = logging.getLogger(__name__)

router = APIRouter()


@router.websocket("/ws/ha")
async def websocket_ha(websocket: WebSocket):
    """WebSocket endpoint for Home Assistant instances.
    
    Auth: ?token=<cloud_token>
    """
    cloud_token = websocket.query_params.get("token")
    if not cloud_token:
        await websocket.close(code=1008, reason="Missing token")
        return
    
    # Validate token against DB
    async with AsyncSessionLocal() as db:
        instance = await db.scalar(
            select(HAInstance).where(HAInstance.cloud_token == cloud_token)
        )
        if not instance:
            await websocket.close(code=1008, reason="Invalid token")
            return
        instance.is_online = True
        await db.commit()
    
    await websocket.accept()
    await ws_manager.connect_ha(cloud_token, websocket)
    await ws_manager.broadcast_to_user(instance.user_id, {"type": "ha_online", "ha_instance_id": instance.id})
    
    try:
        while True:
            # Use receive_text to support string ping/pong
            raw = await websocket.receive_text()
            if raw == "ping":
                await websocket.send_text("pong")
            else:
                # Try to parse as JSON for HA->cloud messages
                try:
                    msg = json.loads(raw)
                    mtype = msg.get("type")
                    if mtype == "ping":
                        await websocket.send_json({"type": "pong"})
                except (json.JSONDecodeError, ValueError):
                    pass
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
                await ws_manager.broadcast_to_user(
                    inst.user_id, {"type": "ha_offline", "ha_instance_id": inst.id},
                )


@router.websocket("/ws/client")
async def websocket_client(websocket: WebSocket):
    """WebSocket endpoint for mobile app clients.
    
    Auth: ?token=<access_jwt>
    """
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
            # We don't expect messages from clients, but keep alive
            msg = await websocket.receive_text()
            if msg == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.exception("Client WS error: %s", e)
    finally:
        await ws_manager.disconnect_client(user_id, websocket)
