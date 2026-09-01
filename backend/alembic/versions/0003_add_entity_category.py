"""add entity_category column to devices

Revision ID: 0003_add_entity_category
Revises: 0002_add_ha_devices
Create Date: 2026-09-02

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = "0003_add_entity_category"
down_revision: Union[str, None] = "0002_add_ha_devices"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "devices",
        sa.Column("entity_category", sa.String(32), nullable=True),
    )
    op.create_index(
        "ix_devices_entity_category",
        "devices",
        ["entity_category"],
    )


def downgrade() -> None:
    op.drop_index("ix_devices_entity_category", table_name="devices")
    op.drop_column("devices", "entity_category")
