"""Home Assistant instance schemas."""
from datetime import datetime
from typing import List, Optional

from pydantic import BaseModel, Field


class HARegisterRequest(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    ha_token: str = Field(min_length=10, description="HA Long-Lived Access Token")


class HARegisterResponse(BaseModel):
    id: int
    name: str
    cloud_token: str
    is_online: bool
    created_at: datetime

    model_config = {"from_attributes": True}


class HAInstanceResponse(BaseModel):
    id: int
    name: str
    is_online: bool
    last_seen: Optional[datetime]
    device_count: int
    created_at: datetime


class HeartbeatRequest(BaseModel):
    timestamp: Optional[datetime] = None


class DeviceSyncItem(BaseModel):
    entity_id: str
    domain: str
    name: str
    state: str
    attributes: dict = Field(default_factory=dict)


class DeviceSyncRequest(BaseModel):
    devices: List[DeviceSyncItem]


class StateReportRequest(BaseModel):
    entity_id: str
    state: str
    attributes: dict = Field(default_factory=dict)
