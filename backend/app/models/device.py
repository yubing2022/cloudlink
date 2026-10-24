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

    ha_instance: Mapped["HAInstance"] = relationship(back_populates="ha_devices")
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
    # HA's entity_category: None=real, "config"=config entry, "diagnostic"=diag
    entity_category: Mapped[Optional[str]] = mapped_column(
        String(32), nullable=True, index=True,
    )
    # Stable identity + semantic signals from HA's entity registry.
    # The cloud labels entities (e.g. "this switch is the indicator
    # light") from these instead of matching the display name, which
    # the user can rename and which is localised per integration.
    #   platform              - integration that created it (xiaomi_miot,
    #                           yeelight, ...); decides which labelling
    #                           rule applies
    #   unique_id             - integration-internal id, stable across
    #                           renames; xiaomi_miot encodes siid/piid
    #   original_device_class - HA's device_class even if the user
    #                           overrode it in the UI
    #   original_name         - HA's original name even if renamed
    # All four are NULL for payloads from older plugins.
    platform: Mapped[Optional[str]] = mapped_column(
        String(128), nullable=True, index=True,
    )
    unique_id: Mapped[Optional[str]] = mapped_column(
        String(255), nullable=True, index=True,
    )
    original_device_class: Mapped[Optional[str]] = mapped_column(
        String(64), nullable=True,
    )
    original_name: Mapped[Optional[str]] = mapped_column(
        String(255), nullable=True,
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
    ha_instance: Mapped["HAInstance"] = relationship(back_populates="entities")

    @property
    def slot(self) -> Optional[str]:
        """Semantic slot label ("main", "power", "child_lock", ...) or None.

        Labelled from platform + unique_id, which are stable across
        renames, instead of from the display name. None means "we can't
        tell" and the App falls back to its own template rules — see
        app/services/entity_labels.py for the rules and why `main` is
        only assigned when an integration-specific rule matches.

        Defined here (rather than passed in at the call site) because
        the device endpoints serialise ORM objects directly via
        `from_attributes`, so this is the one place that can feed every
        consumer — App, capability and MCP alike.
        """
        from app.services.entity_labels import entity_slot

        return entity_slot(
            self.platform, self.unique_id, self.domain, self.original_device_class,
        )

    def __repr__(self) -> str:
        return f"<DeviceEntity {self.entity_id} state={self.state!r}>"
