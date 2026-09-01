"""Device models: HADevice (HA device registry) + DeviceEntity (HA entity state)."""
from datetime import datetime
from typing import TYPE_CHECKING, Any, Optional

from sqlalchemy import JSON, DateTime, ForeignKey, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base

if TYPE_CHECKING:
    from app.models.ha_instance import HAInstance


class HADevice(Base):
    """Represents a physical/logical device from HA's device registry.

    One HA device can have multiple entities (e.g., a smart plug has
    switch + button + sensor entities all linked to the same device).
    """
    __tablename__ = "ha_devices"

    id: Mapped[int] = mapped_column(primary_key=True)
    ha_instance_id: Mapped[int] = mapped_column(
        ForeignKey("ha_instances.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    # HA's device registry id (a hex string like "abc123def456")
    ha_device_id: Mapped[str] = mapped_column(String(128), nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False, default="Unknown")
    manufacturer: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    model: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    area: Mapped[Optional[str]] = mapped_column(String(100), nullable=True, index=True)
    sw_version: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    hw_version: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default="now()", nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default="now()", nullable=False,
    )

    __table_args__ = (
        UniqueConstraint("ha_instance_id", "ha_device_id", name="uq_ha_device"),
    )

    entities: Mapped[list["DeviceEntity"]] = relationship(
        back_populates="device",
        cascade="all, delete-orphan",
    )

    def __repr__(self) -> str:
        return f"<HADevice {self.id} {self.name!r}>"


class DeviceEntity(Base):
    """An HA entity (state) - one row per entity_id.

    Previously called "Device", renamed to reflect that it represents
    an entity, not a physical device. Multiple entities of the same
    device (e.g., switch + button + sensor) link to the same HADevice.
    """
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(primary_key=True)
    ha_instance_id: Mapped[int] = mapped_column(
        ForeignKey("ha_instances.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    # Optional link to HADevice (NULL for entities without a device_id
    # or before this migration runs)
    ha_device_pk: Mapped[Optional[int]] = mapped_column(
        ForeignKey("ha_devices.id", ondelete="SET NULL"),
        index=True,
        nullable=True,
    )
    entity_id: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    domain: Mapped[str] = mapped_column(String(50), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False, default="")
    state: Mapped[str] = mapped_column(String(100), nullable=False, default="unknown")
    attributes: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    last_state_change: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), nullable=True,
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default="now()", nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default="now()", nullable=False,
    )

    __table_args__ = (
        UniqueConstraint("ha_instance_id", "entity_id", name="uq_device_entity"),
    )

    device: Mapped[Optional["HADevice"]] = relationship(back_populates="entities")

    def __repr__(self) -> str:
        return f"<DeviceEntity {self.entity_id} state={self.state!r}>"
