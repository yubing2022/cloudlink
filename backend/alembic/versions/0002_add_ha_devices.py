"""add ha_devices table and link devices to ha_devices

Revision ID: 0002_add_ha_devices
Revises: 0001_initial
Create Date: 2026-09-01

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = "0002_add_ha_devices"
down_revision: Union[str, None] = "0001_initial"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Create ha_devices table
    op.create_table(
        "ha_devices",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("ha_instance_id", sa.Integer(),
                  sa.ForeignKey("ha_instances.id", ondelete="CASCADE"),
                  index=True, nullable=False),
        sa.Column("ha_device_id", sa.String(128), nullable=False),
        sa.Column("name", sa.String(255), nullable=False, server_default="Unknown"),
        sa.Column("manufacturer", sa.String(128), nullable=True),
        sa.Column("model", sa.String(128), nullable=True),
        sa.Column("area", sa.String(100), nullable=True, index=True),
        sa.Column("sw_version", sa.String(64), nullable=True),
        sa.Column("hw_version", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("ha_instance_id", "ha_device_id", name="uq_ha_device"),
    )

    # 2. Add ha_device_pk column to devices (nullable, no cascade)
    op.add_column(
        "devices",
        sa.Column("ha_device_pk", sa.Integer(), nullable=True),
    )
    op.create_foreign_key(
        "fk_devices_ha_device",
        "devices", "ha_devices",
        ["ha_device_pk"], ["id"],
        ondelete="SET NULL",
    )
    op.create_index(
        "ix_devices_ha_device_pk",
        "devices",
        ["ha_device_pk"],
    )


def downgrade() -> None:
    op.drop_index("ix_devices_ha_device_pk", table_name="devices")
    op.drop_constraint("fk_devices_ha_device", "devices", type_="foreignkey")
    op.drop_column("devices", "ha_device_pk")
    op.drop_table("ha_devices")
