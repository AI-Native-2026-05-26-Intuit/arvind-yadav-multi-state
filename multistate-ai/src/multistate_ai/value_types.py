"""Internal value types - frozen dataclasses with slots.

These are pure in-memory value objects the sidecar builds itself.
They never cross an external boundary; that is what Pydantic models
in models.py are for.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True, slots=True)
class ProxyCallKey:
    """Cache key for an in-flight proxy call. Hashable by value."""

    correlation_id: str
    model_id: str
    prompt_hash: str


@dataclass(frozen=True, slots=True)
class CorrelationContext:
    """The W3 D2 correlation-id carrier. Propagates through structured logs."""

    correlation_id: str
    tenant_id: str
    started_at: datetime
    # Tuple, not list - immutability is end to end.
    tags: tuple[str, ...] = ()
