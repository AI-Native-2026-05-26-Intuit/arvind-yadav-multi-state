"""Pydantic v2 boundary models for the multistate-api sidecar.

Minimal Tenant + NexusReviewRequest for Task 1 CLI validation.
Expanded in Task 2 with validators, NexusReviewResult, and contract tests.
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field


class Tenant(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    """Mirror of the Java Tenant record at the JSON boundary."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        str_strip_whitespace=True,
        populate_by_name=True,
    )

    id: str = Field(min_length=1, max_length=64)
    tenant_id: str = Field(min_length=1, alias="tenantId")
    created_at: datetime = Field(alias="createdAt")
    amount: Decimal = Field(ge=Decimal("0"), max_digits=14, decimal_places=2)


class NexusReviewRequest(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    """Boundary request for nexus review."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    correlation_id: str = Field(min_length=1, alias="correlationId")
    tenant: Tenant
    model_id: str = Field(default="anthropic.claude-3-5-sonnet-20241022-v2:0")
