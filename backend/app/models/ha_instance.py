"""HAInstance model: a registered Home Assistant instance belonging to a user."""
import secrets
from datetime import datetime
from typing import TYPE_CHECKING, List, Optional

from sqlalchemy import Boolean, DateTime, ForeignKey, LargeBinary, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import TimestampMixin

if TYPE_CHECKING:
    from app.models.device import Device
    from app.models.user import User


def _generate_cloud_token() -> str:
    """Generate a secure random cloud_token."""
    return secrets.token_urlsafe(32)


class HAInstance(Base, TimestampMixin):
    __tablename__ = "ha_instances"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    cloud_token: Mapped[str] = mapped_column(
        String(255), unique=True, index=True, nullable=False,
        default=_generate_cloud_token,
    )
    encrypted_ha_token: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    last_seen: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), nullable=True,
    )
    is_online: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    user: Mapped["User"] = relationship("User", back_populates="ha_instances")
    devices: Mapped[List["Device"]] = relationship(
        "Device",
        back_populates="ha_instance",
        cascade="all, delete-orphan",
    )

    def __repr__(self) -> str:
        return f"<HAInstance id={self.id} name={self.name!r} online={self.is_online}>"
