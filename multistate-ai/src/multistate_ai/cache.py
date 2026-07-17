"""Redis semantic cache keyed by (tenant_id, epoch, quantised-embedding).

Tenant isolation rule: tenant_id is part of the cache key AND a defence
in depth check at lookup time asserts every citation in the cached answer
belongs to the requesting tenant.
"""

from __future__ import annotations

import hashlib
import json
from typing import Final, cast

import numpy as np
import redis
from langsmith import traceable
from numpy.typing import NDArray

SEMANTIC_THRESHOLD: Final = 0.05


def _bucket_key(query_vec: NDArray[np.float32], tenant_id: str, epoch: int) -> str:
    # Round the embedding to coarse buckets so near-duplicates collide.
    quantised = np.round(query_vec * 100).astype(np.int32).tobytes()
    h = hashlib.sha256(quantised).hexdigest()[:16]
    return f"multistate_ai:sem:{tenant_id}:e{epoch}:{h}"


def _as_str(raw: bytes | str | int | float) -> str:
    if isinstance(raw, bytes):
        return raw.decode("utf-8")
    return str(raw)


def get_epoch(r: redis.Redis, tenant_id: str) -> int:
    raw = r.get(f"multistate_ai:cache-epoch:{tenant_id}")
    if raw is None:
        return 0
    return int(_as_str(cast(bytes | str | int | float, raw)))


def bump_epoch(r: redis.Redis, tenant_id: str) -> int:
    # Called from the Airflow upsert_chunks task on completion per tenant.
    return int(r.incr(f"multistate_ai:cache-epoch:{tenant_id}"))


@traceable(run_type="chain", name="multistate_ai.cache_lookup")
def cache_lookup(
    r: redis.Redis,
    query_vec: NDArray[np.float32],
    tenant_id: str,
) -> dict[str, object] | None:
    epoch = get_epoch(r, tenant_id)
    raw = r.get(_bucket_key(query_vec, tenant_id, epoch))
    if not raw:
        return None
    payload = raw.decode("utf-8") if isinstance(raw, bytes) else str(raw)
    answer = cast(dict[str, object], json.loads(payload))
    # Defence-in-depth: every citation must belong to the requesting tenant.
    citations = answer.get("citations", [])
    if isinstance(citations, list):
        for cite in citations:
            if not isinstance(cite, dict):
                return None
            if cite.get("tenant_id") != tenant_id:
                return None  # treat tenant-mismatch as a cache miss
    return answer


def cache_store(
    r: redis.Redis,
    query_vec: NDArray[np.float32],
    tenant_id: str,
    answer: dict[str, object],
    ttl_seconds: int = 3600,
) -> None:
    epoch = get_epoch(r, tenant_id)
    r.set(
        _bucket_key(query_vec, tenant_id, epoch),
        json.dumps(answer),
        ex=ttl_seconds,
    )
