"""Aggregate API router."""
from fastapi import APIRouter

from app.api import auth, devices, ha, ws

api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(ha.user_router)
api_router.include_router(ha.ha_router)
api_router.include_router(devices.router)
api_router.include_router(ws.router)
try:
    from app.api.icons import router as icons_router
    api_router.include_router(icons_router)
except ImportError:
    pass
