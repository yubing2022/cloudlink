"""FastAPI application entry point."""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api.router import api_router
from app.config import settings
from app.core.logging import configure_logging
from app.database import engine
from app.ws.manager import ws_manager


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: startup + shutdown."""
    configure_logging()
    # Start WebSocket manager
    await ws_manager.start()
    yield
    # Cleanup
    await ws_manager.stop()
    await engine.dispose()


app = FastAPI(
    title="CloudLink",
    description="Cloud relay service for Home Assistant devices",
    version=__version__,
    lifespan=lifespan,
    debug=settings.DEBUG,
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Routes
app.include_router(api_router, prefix="/api")


@app.get("/")
async def root():
    return {
        "name": "CloudLink",
        "version": __version__,
        "status": "running",
        "environment": settings.ENVIRONMENT,
    }


@app.get("/health")
async def health():
    return {"status": "ok"}
