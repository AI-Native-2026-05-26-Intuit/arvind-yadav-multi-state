"""Semantic cache smoke tests (Testcontainers Redis)."""

from __future__ import annotations

from collections.abc import Iterator

import numpy as np
import pytest
import redis
from numpy.typing import NDArray
from testcontainers.redis import RedisContainer

from multistate_ai.cache import bump_epoch, cache_lookup, cache_store, get_epoch
from multistate_ai.corpus import EMBEDDING_DIM


@pytest.fixture(scope="module")
def redis_client() -> Iterator[redis.Redis]:
    with RedisContainer("redis:7") as redis_c:
        client = redis_c.get_client()
        yield client


def _near_dup_pair() -> tuple[NDArray[np.float32], NDArray[np.float32]]:
    """Two vectors that share the same np.round(v*100) quantisation buckets."""
    base = np.linspace(-0.5, 0.5, EMBEDDING_DIM, dtype=np.float32)
    buckets = np.round(base * 100).astype(np.int32)
    twin = (buckets.astype(np.float32) / np.float32(100.0)) + np.float32(1e-6)
    return base, twin.astype(np.float32)


def test_near_duplicate_vectors_hit_same_cache_key(redis_client: redis.Redis) -> None:
    v1, v2 = _near_dup_pair()
    answer: dict[str, object] = {
        "text": "California nexus is $500k.",
        "citations": [{"doc_id": "d1", "tenant_id": "tenant-a"}],
    }
    cache_store(redis_client, v1, "tenant-a", answer)
    hit = cache_lookup(redis_client, v2, "tenant-a")
    assert hit is not None
    assert hit["text"] == answer["text"]


def test_tenant_b_does_not_hit_tenant_a_entry(redis_client: redis.Redis) -> None:
    v1, v2 = _near_dup_pair()
    cache_store(
        redis_client,
        v1,
        "tenant-a",
        {
            "text": "tenant-a answer",
            "citations": [{"doc_id": "d1", "tenant_id": "tenant-a"}],
        },
    )
    miss = cache_lookup(redis_client, v2, "tenant-b")
    assert miss is None


def test_bump_epoch_invalidates_prior_key(redis_client: redis.Redis) -> None:
    v1, _v2 = _near_dup_pair()
    cache_store(
        redis_client,
        v1,
        "tenant-a",
        {
            "text": "before bump",
            "citations": [{"doc_id": "d1", "tenant_id": "tenant-a"}],
        },
    )
    assert cache_lookup(redis_client, v1, "tenant-a") is not None
    before = get_epoch(redis_client, "tenant-a")
    after = bump_epoch(redis_client, "tenant-a")
    assert after == before + 1
    assert cache_lookup(redis_client, v1, "tenant-a") is None


def test_citation_tenant_mismatch_is_cache_miss(redis_client: redis.Redis) -> None:
    v1, _ = _near_dup_pair()
    cache_store(
        redis_client,
        v1,
        "tenant-a",
        {
            "text": "leaky",
            "citations": [{"doc_id": "d1", "tenant_id": "tenant-b"}],
        },
    )
    assert cache_lookup(redis_client, v1, "tenant-a") is None
