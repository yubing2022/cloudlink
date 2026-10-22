"""add entity registry fields (platform, unique_id, original_*) to devices

These come from HA's entity registry and, unlike `name` / `attributes`,
are stable across renames. The cloud needs them to label entities by
integration instead of matching a localised display name.

Revision ID: 0004_add_entity_registry_fields
Revises: 0003_add_entity_category
Create Date: 2026-10-22

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = "0004_add_entity_registry_fields"
down_revision: Union[str, None] = "0003_add_entity_category"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, None] = None


def upgrade() -> None:
    op.add_column(
        "devices",
        sa.Column("platform", sa.String(128), nullable=True),
    )
    op.add_column(
        "devices",
        sa.Column("unique_id", sa.String(255), nullable=True),
    )
    op.add_column(
        "devices",
        sa.Column("original_device_class", sa.String(64), nullable=True),
    )
    op.add_column(
        "devices",
        sa.Column("original_name", sa.String(255), nullable=True),
    )
    op.create_index("ix_devices_platform", "devices", ["platform"])
    op.create_index("ix_devices_unique_id", "devices", ["unique_id"])


def downgrade() -> None:
    op.drop_index("ix_devices_unique_id", table_name="devices")
    op.drop_index("ix_devices_platform", table_name="devices")
    op.drop_column("devices", "original_name")
    op.drop_column("devices", "original_device_class")
    op.drop_column("devices", "unique_id")
    op.drop_column("devices", "platform")
