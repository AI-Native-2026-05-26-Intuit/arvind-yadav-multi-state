from __future__ import annotations

import dataclasses
from datetime import UTC, datetime

import pytest

from multistate_ai.value_types import CorrelationContext, ProxyCallKey


def test_proxy_call_key_is_hashable() -> None:
    key = ProxyCallKey(
        correlation_id="corr-abc",
        model_id="anthropic.claude-3-5-sonnet-20241022-v2:0",
        prompt_hash="deadbeef",
    )
    assert hash(key) == hash(key)
    assert key in {key}


def test_correlation_context_uses_tuple_tags() -> None:
    started = datetime(2026, 1, 15, 12, 0, tzinfo=UTC)
    context = CorrelationContext(
        correlation_id="corr-abc",
        tenant_id="tenant-a",
        started_at=started,
        tags=("nexus", "review"),
    )
    assert context.tags == ("nexus", "review")


def test_value_types_are_frozen() -> None:
    key = ProxyCallKey("corr-1", "model", "hash")
    with pytest.raises(dataclasses.FrozenInstanceError):
        key.correlation_id = "corr-2"  # type: ignore[misc]
