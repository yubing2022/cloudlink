"""Pydantic schemas for devices and entities."""
from datetime import datetime
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class DeviceEntitySchema(BaseModel):
    """One HA entity (e.g., switch.cuco_cn_..._on_p_2_1)."""
    id: int
    entity_id: str
    domain: str
    name: str
    state: str
    attributes: Dict[str, Any] = Field(default_factory=dict)
    last_state_change: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    model_config = {"from_attributes": True}


class HADeviceSchema(BaseModel):
    """One HA physical/logical device with all its entities."""
    id: int
    ha_device_id: str
    name: str
    manufacturer: Optional[str] = None
    model: Optional[str] = None
    area: Optional[str] = None
    sw_version: Optional[str] = None
    hw_version: Optional[str] = None
    entities: List[DeviceEntitySchema] = Field(default_factory=list)
    updated_at: Optional[datetime] = None

    model_config = {"from_attributes": True}


class DeviceStateUpdate(BaseModel):
    """Real-time state change (from WebSocket)."""
    type: str = "state_change"
    entity_id: str
    state: str
    attributes: Dict[str, Any] = Field(default_factory=dict)


class DeviceActionRequest(BaseModel):
    domain: str
    service: str
    data: Dict[str, Any] = Field(default_factory=dict)
