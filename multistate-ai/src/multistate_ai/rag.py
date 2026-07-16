"""Retrieval surface for the sidecar; @traceable streams runs to LangSmith.

The LangSmith client reads LANGSMITH_API_KEY and LANGSMITH_PROJECT
from env at boot. Never hardcode the key; the production runtime
supplies it via the same secrets mechanism as the LLM proxy key.
"""

from __future__ import annotations

import os
from functools import lru_cache
from typing import Final

import numpy as np
import psycopg
from langsmith import traceable
from numpy.typing import NDArray
from pgvector.psycopg import register_vector
from sentence_transformers import SentenceTransformer

from .corpus import MODEL_NAME
from .pgvector_loader import dsn_from_env

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


@traceable(run_type="retriever", name=_RETRIEVER_NAME)
def retrieve_chunks(
    dsn: str,
    question: str,
    k: int = 5,
    tenant_id: str = "tenant-a",
    model_version: str = MODEL_NAME,
) -> list[dict[str, object]]:
    """Embed the question, run ANN search, return top-k chunks.

    The @traceable decorator captures inputs, outputs, latency, and the
    span hierarchy in the LangSmith project named by LANGSMITH_PROJECT.
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
            # Cosine operator (<=>) matches the HNSW op-class vector_cosine_ops.
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
