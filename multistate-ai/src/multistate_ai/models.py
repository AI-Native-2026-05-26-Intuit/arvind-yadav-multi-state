"""Pydantic v2 boundary models for the multistate-api sidecar.

These models mirror the Java domain on the multistate-api side. The JSON
emitted by ``model_dump_json()`` round-trips against the Java service's
JSON output for Tenant. See tests/test_models.py.
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


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
    # Money is Decimal, not float - matches Java BigDecimal on the wire.
    amount: Decimal = Field(ge=Decimal("0"), max_digits=14, decimal_places=2)

    @field_validator("tenant_id")
    @classmethod
    def _tenant_must_have_prefix(cls, v: str) -> str:
        if not v.startswith("tenant-"):
            raise ValueError("tenant_id must start with 'tenant-'")
        return v


class NexusReviewRequest(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    """Boundary request for nexus review."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    correlation_id: str = Field(min_length=1, alias="correlationId")
    tenant: Tenant
    model_id: str = Field(default="anthropic.claude-3-5-sonnet-20241022-v2:0")

    @field_validator("correlation_id")
    @classmethod
    def _correlation_id_shape(cls, v: str) -> str:
        if not v.startswith("corr-"):
            raise ValueError("correlation_id must start with 'corr-'")
        return v


class NexusReviewResult(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    """Boundary response for nexus review."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    correlation_id: str = Field(min_length=1, alias="correlationId")
    tenant_id: str = Field(min_length=1)
    label: str = Field(min_length=1, max_length=64)
    confidence: float = Field(ge=0.0, le=1.0)
    rationale: str = Field(min_length=1, max_length=1024)

    @model_validator(mode="after")
    def _high_confidence_requires_rationale(self) -> NexusReviewResult:
        if self.confidence >= 0.9 and len(self.rationale) < 16:
            raise ValueError(
                "high-confidence results require a rationale of >=16 chars"
            )
        return self
