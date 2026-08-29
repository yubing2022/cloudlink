"""Device schemas."""
from datetime import datetime
from typing import Any, Dict, Optional

from pydantic import BaseModel


class DeviceResponse(BaseModel):
    id: int
    entity_id: str
    domain: str
    name: str
    state: str
    attributes: Dict[str, Any]
    capabilities: Dict[str, Any]
    area: Optional[str]
    ha_instance_id: int
    updated_at: datetime

    model_config = {"from_attributes": True}


class DeviceActionRequest(BaseModel):
    domain: str
    service: str
    data: Dict[str, Any] = {}
