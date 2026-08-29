"""Initial schema: users, ha_instances, devices

Revision ID: 0001_initial
Revises: 
Create Date: 2026-08-30

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB


revision: str = '0001_initial'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'users',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('email', sa.String(255), nullable=False, unique=True, index=True),
        sa.Column('password_hash', sa.String(255), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        'ha_instances',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('user_id', sa.Integer(), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False, index=True),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('cloud_token', sa.String(255), nullable=False, unique=True, index=True),
        sa.Column('encrypted_ha_token', sa.LargeBinary(), nullable=False),
        sa.Column('last_seen', sa.DateTime(timezone=True), nullable=True),
        sa.Column('is_online', sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        'devices',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('ha_instance_id', sa.Integer(), sa.ForeignKey('ha_instances.id', ondelete='CASCADE'), nullable=False, index=True),
        sa.Column('entity_id', sa.String(255), nullable=False, index=True),
        sa.Column('domain', sa.String(50), nullable=False, index=True),
        sa.Column('name', sa.String(255), nullable=False),
        sa.Column('state', sa.String(100), nullable=False, server_default='unknown'),
        sa.Column('attributes', JSONB(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column('capabilities', JSONB(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column('area', sa.String(100), nullable=True),
        sa.Column('last_state_change', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint('ha_instance_id', 'entity_id', name='uq_device_entity'),
    )


def downgrade() -> None:
    op.drop_table('devices')
    op.drop_table('ha_instances')
    op.drop_table('users')
