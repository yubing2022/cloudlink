"""Device model: a HA entity (light, switch, sensor, etc.) belonging to an HA instance."""
from datetime import datetime
from typing import TYPE_CHECKING, Any, Optional

from sqlalchemy import JSON, DateTime, ForeignKey, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import TimestampMixin

if TYPE_CHECKING:
    from app.models.ha_instance import HAInstance


class Device(Base, TimestampMixin):
    __tablename__ = "devices"
    __table_args__ = (
        UniqueConstraint("ha_instance_id", "entity_id", name="uq_device_entity"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    ha_instance_id: Mapped[int] = mapped_column(
        ForeignKey("ha_instances.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    entity_id: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    domain: Mapped[str] = mapped_column(String(50), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    state: Mapped[str] = mapped_column(String(100), nullable=False, default="unknown")
    attributes: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict, nullable=False)
    capabilities: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict, nullable=False)
    area: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    last_state_change: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), nullable=True,
        server_default=func.now(),
    )

    ha_instance: Mapped["HAInstance"] = relationship("HAInstance", back_populates="devices")

    def __repr__(self) -> str:
        return f"<Device id={self.id} {self.entity_id} state={self.state!r}>"
