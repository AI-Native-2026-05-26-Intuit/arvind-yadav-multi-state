from __future__ import annotations

import json
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

import pytest
from pydantic import ValidationError

from multistate_ai.models import NexusReviewResult, Tenant

_FIXTURES = Path(__file__).parent / "fixtures"


def test_tenant_validates_tenant_prefix() -> None:
    with pytest.raises(ValidationError) as excinfo:
        Tenant(
            id="tenant-001",
            tenantId="acme-a",
            createdAt=datetime(2026, 1, 15, 12, 0, tzinfo=UTC),
            amount=Decimal("0"),
        )
    assert "tenant_id must start with" in str(excinfo.value)


@pytest.mark.parametrize(
    ("amount", "ok"),
    [
        (Decimal("0"), True),
        (Decimal("0.01"), True),
        (Decimal("-0.01"), False),
        (Decimal("-1"), False),
    ],
)
def test_tenant_amount_must_be_non_negative(amount: Decimal, ok: bool) -> None:
    created = datetime(2026, 1, 15, 12, 0, tzinfo=UTC)
    if ok:
        Tenant(
            id="tenant-001",
            tenantId="tenant-a",
            createdAt=created,
            amount=amount,
        )
    else:
        with pytest.raises(ValidationError):
            Tenant(
                id="tenant-001",
                tenantId="tenant-a",
                createdAt=created,
                amount=amount,
            )


def test_result_high_confidence_requires_long_rationale() -> None:
    with pytest.raises(ValidationError):
        NexusReviewResult(
            correlationId="corr-1",
            tenant_id="tenant-001",
            label="standard",
            confidence=0.95,
            rationale="short",
        )


def test_round_trip_against_java_fixture() -> None:
    """Pydantic must read JSON the Java side emits and write JSON it can read."""
    fixture_path = _FIXTURES / "tenant_java.json"
    java_bytes = fixture_path.read_bytes()
    parsed = Tenant.model_validate_json(java_bytes)
    dumped = json.loads(parsed.model_dump_json(by_alias=True))
    original = json.loads(java_bytes)
    assert dumped == original
    again = Tenant.model_validate_json(parsed.model_dump_json(by_alias=True).encode())
    assert again == parsed
