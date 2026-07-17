"""Hybrid retrieval: dense (pgvector) + sparse (Postgres FTS) fused via RRF.

Score-based fusion mixes incomparable scales (cosine in [-1, 1], BM25-shaped
ts_rank_cd is unbounded positive). Reciprocal Rank Fusion sidesteps the
problem entirely: it works on rank position, not raw score, so the fusion
is stable across corpora and query types.

Fusion keys on ``chunk_id`` (BIGSERIAL PK), not ``doc_id``, so multi-chunk
documents do not collapse under a shared parent id.
"""

from __future__ import annotations

import json
from collections import defaultdict
from typing import Final, NamedTuple

import numpy as np
import psycopg
from langsmith import traceable
from numpy.typing import NDArray

K_CONST: Final = 60  # Topic 7 default; tune weights not k.


class RetrievedChunk(NamedTuple):
    """One retrieval hit: unique chunk_id plus parent doc_id for citations."""

    chunk_id: str
    doc_id: str
    chunk_text: str
    score: float


@traceable(run_type="retriever", name="multistate_ai.dense_topk_filtered")
def dense_topk_filtered(
    conn: psycopg.Connection,
    query_vec: NDArray[np.float32],
    tenant_id: str,
    metadata_filter: dict[str, str] | None = None,
    k: int = 50,
    model_version: str = "all-MiniLM-L6-v2",
) -> list[RetrievedChunk]:
    # Pre-filter on tenant_id (hits the per-tenant partial HNSW from V002)
    # AND on model_version (no mixed-model rows in the candidate pool).
    sql = (
        "SELECT chunk_id::text, doc_id, chunk_text, embedding <=> %s AS dist "
        "FROM doc_chunks "
        "WHERE tenant_id = %s AND model_version = %s "
    )
    params: list[object] = [query_vec, tenant_id, model_version]
    if metadata_filter:
        sql += "AND chunk_metadata @> %s::jsonb "
        params.append(json.dumps(metadata_filter))
    sql += "ORDER BY embedding <=> %s LIMIT %s"
    params.extend([query_vec, k])
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return [
            RetrievedChunk(str(r[0]), str(r[1]), str(r[2]), float(r[3])) for r in cur.fetchall()
        ]


@traceable(run_type="retriever", name="multistate_ai.sparse_topk_fts")
def sparse_topk_fts(
    conn: psycopg.Connection,
    query_text: str,
    tenant_id: str,
    k: int = 50,
) -> list[RetrievedChunk]:
    # Postgres FTS fallback (Topic 6) when OpenSearch is unavailable.
    # websearch_to_tsquery accepts Google-style quoting and exclusion.
    sql = (
        "SELECT chunk_id::text, doc_id, chunk_text, ts_rank_cd(chunk_tsv, q) AS score "
        "FROM doc_chunks, websearch_to_tsquery('english', %s) AS q "
        "WHERE tenant_id = %s AND chunk_tsv @@ q "
        "ORDER BY score DESC LIMIT %s"
    )
    with conn.cursor() as cur:
        cur.execute(sql, (query_text, tenant_id, k))
        return [
            RetrievedChunk(str(r[0]), str(r[1]), str(r[2]), float(r[3])) for r in cur.fetchall()
        ]


@traceable(run_type="chain", name="multistate_ai.rrf_fuse")
def rrf_fuse(
    dense: list[RetrievedChunk],
    sparse: list[RetrievedChunk],
    k_const: int = K_CONST,
    w_dense: float = 1.0,
    w_sparse: float = 1.0,
    top_k: int = 60,
) -> list[RetrievedChunk]:
    # Rank-based fusion keyed by chunk_id; raw scores ignored.
    scores: dict[str, float] = defaultdict(float)
    by_id: dict[str, RetrievedChunk] = {}
    for rank, hit in enumerate(dense, start=1):
        scores[hit.chunk_id] += w_dense / (k_const + rank)
        by_id[hit.chunk_id] = hit
    for rank, hit in enumerate(sparse, start=1):
        scores[hit.chunk_id] += w_sparse / (k_const + rank)
        by_id.setdefault(hit.chunk_id, hit)
    ordered = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    return [
        RetrievedChunk(cid, by_id[cid].doc_id, by_id[cid].chunk_text, score)
        for cid, score in ordered[:top_k]
    ]


def coverage(
    dense: list[RetrievedChunk],
    sparse: list[RetrievedChunk],
) -> dict[str, float]:
    # Diagnostic logged on every request: dense/sparse Jaccard agreement.
    d_ids = {h.chunk_id for h in dense}
    s_ids = {h.chunk_id for h in sparse}
    both = d_ids & s_ids
    return {
        "dense_only": float(len(d_ids - s_ids)),
        "sparse_only": float(len(s_ids - d_ids)),
        "both": float(len(both)),
        "jaccard": float(len(both)) / float(len(d_ids | s_ids) or 1),
    }
