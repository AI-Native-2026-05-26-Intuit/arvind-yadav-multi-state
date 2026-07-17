"""retrieve_and_generate — the W7D3 entry point — plus W7D2 retrieve_chunks.

Five stages plus the semantic-cache check. The same function will be
published by Thursday's MCP server and called from Friday's LangGraph
agents. Pinning this signature today keeps both downstream days cheap.
"""

from __future__ import annotations

import os
from functools import lru_cache
from typing import Final

import numpy as np
import psycopg
import redis
from anthropic import Anthropic
from langsmith import traceable
from numpy.typing import NDArray
from pgvector.psycopg import register_vector
from sentence_transformers import SentenceTransformer

from .cache import cache_lookup, cache_store
from .corpus import MODEL_NAME
from .hybrid import RetrievedChunk, dense_topk_filtered, rrf_fuse, sparse_topk_fts
from .pgvector_loader import dsn_from_env
from .rerank import bge_rerank, mmr_pick

_RETRIEVER_NAME: Final = "multistate_ai.retrieve_chunks"


@lru_cache(maxsize=1)
def _embedding_model() -> SentenceTransformer:
    """Load MiniLM once; lazy so importing this module does not download weights."""
    model: SentenceTransformer = SentenceTransformer(MODEL_NAME)
    return model


def _require_langsmith_api_key() -> None:
    """Fail at boot (first call), not deep inside the ANN path — cheaper signal."""
    if "LANGSMITH_API_KEY" not in os.environ or not os.environ["LANGSMITH_API_KEY"]:
        raise RuntimeError("LANGSMITH_API_KEY must be set in env")


def _validate_query_and_tenant(query_text: str, tenant_id: str) -> None:
    if not query_text.strip():
        raise ValueError("query_text must be non-empty")
    if not tenant_id.strip():
        raise ValueError("tenant_id must be non-empty")


@traceable(run_type="retriever", name=_RETRIEVER_NAME)
def retrieve_chunks(
    dsn: str,
    question: str,
    k: int = 5,
    tenant_id: str = "tenant-a",
    model_version: str = MODEL_NAME,
) -> list[dict[str, object]]:
    """Embed the question, run ANN search, return top-k chunks.

    Kept from W7 D2 for LangSmith visibility asserts and unit tests.
    """
    if k <= 0:
        raise ValueError("k must be > 0")
    if not question.strip():
        raise ValueError("question must be non-empty")

    _require_langsmith_api_key()
    q_vec: NDArray[np.float32] = (
        _embedding_model()
        .encode(
            [question],
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        .astype(np.float32)[0]
    )
    with psycopg.connect(dsn) as conn:
        register_vector(conn)
        with conn.cursor() as cur:
            cur.execute(
                "SELECT doc_id, chunk_idx, chunk_text, embedding <=> %s AS dist "
                "FROM doc_chunks "
                "WHERE tenant_id = %s AND model_version = %s "
                "ORDER BY embedding <=> %s LIMIT %s",
                (q_vec, tenant_id, model_version, q_vec, k),
            )
            return [
                {
                    "doc_id": r[0],
                    "chunk_idx": int(r[1]),
                    "chunk_text": r[2],
                    "distance": float(r[3]),
                }
                for r in cur.fetchall()
            ]


def retrieve_chunks_from_env(
    question: str,
    k: int = 5,
    tenant_id: str = "tenant-a",
    model_version: str = MODEL_NAME,
) -> list[dict[str, object]]:
    """Production helper: resolve ``MULTISTATE_AI_PG_DSN`` then retrieve."""
    return retrieve_chunks(
        dsn_from_env(),
        question,
        k=k,
        tenant_id=tenant_id,
        model_version=model_version,
    )


@traceable(run_type="chain", name="multistate_ai.retrieve_and_generate")
def retrieve_and_generate(
    query_text: str,
    tenant_id: str,
    *,
    anthropic: Anthropic,
    conn: psycopg.Connection,
    r: redis.Redis,
    metadata_filter: dict[str, str] | None = None,
    model_name: str = "claude-sonnet-4-5",
    use_hybrid: bool = True,
    use_mmr: bool = True,
    use_rerank: bool = True,
    use_filter: bool = True,
) -> dict[str, object]:
    _validate_query_and_tenant(query_text, tenant_id)
    _require_langsmith_api_key()
    qvec: NDArray[np.float32] = (
        _embedding_model()
        .encode(
            [query_text],
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        .astype(np.float32)[0]
    )

    cached = cache_lookup(r, qvec, tenant_id)
    if cached:
        return cached

    register_vector(conn)
    dense = dense_topk_filtered(
        conn,
        qvec,
        tenant_id,
        metadata_filter=metadata_filter if use_filter else None,
        k=50,
    )
    sparse = sparse_topk_fts(conn, query_text, tenant_id, k=50) if use_hybrid else []
    fused = rrf_fuse(dense, sparse, top_k=60) if use_hybrid else dense[:60]
    diversified = mmr_pick(qvec, fused, _embedding_model(), k=20) if use_mmr else fused[:20]
    if use_rerank:
        reranked, timed_out = bge_rerank(query_text, diversified, top_k=6)
    else:
        reranked, timed_out = diversified[:6], False

    # Generation pinned to claude-sonnet-4-5 per Topic 12 entry point.
    msg = anthropic.messages.create(
        model=model_name,
        max_tokens=512,
        messages=[
            {
                "role": "user",
                "content": _build_prompt(query_text, reranked),
            }
        ],
    )
    text = ""
    if msg.content:
        block = msg.content[0]
        text = getattr(block, "text", "") or ""
    answer: dict[str, object] = {
        "text": text,
        "citations": [
            {
                "chunk_id": hit.chunk_id,
                "doc_id": hit.doc_id,
                "chunk_text": hit.chunk_text,
                "score": hit.score,
                "tenant_id": tenant_id,
            }
            for hit in reranked
        ],
        "rerank_timed_out": timed_out,
    }
    cache_store(r, qvec, tenant_id, answer)
    return answer


def _build_prompt(query_text: str, ctx: list[RetrievedChunk]) -> str:
    parts = [f"[{i}] {hit.chunk_text}" for i, hit in enumerate(ctx, 1)]
    return (
        "Answer the question using only the numbered context below.\n\n"
        + "\n\n".join(parts)
        + f"\n\nQuestion: {query_text}"
    )
