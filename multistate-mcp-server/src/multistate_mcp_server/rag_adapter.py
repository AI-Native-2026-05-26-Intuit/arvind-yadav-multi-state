# multistate-mcp-server/src/multistate_mcp_server/rag_adapter.py
"""Thin adapter: (question, tenant_id, top_k) -> pre-shaped RagAnswer dict.

Wraps multistate_ai.rag.retrieve_and_generate which needs Anthropic +
Postgres + Redis kwargs the MCP tool surface does not expose.
"""

from __future__ import annotations

import os
from typing import cast

import psycopg
import redis
from anthropic import Anthropic
from multistate_ai.pgvector_loader import dsn_from_env
from multistate_ai.rag import retrieve_and_generate


def rag_adapter(question: str, tenant_id: str, top_k: int = 6) -> dict[str, object]:
    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    )
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY / MULTISTATE_AI_ANTHROPIC_API_KEY required")

    redis_url = os.environ.get("MULTISTATE_AI_REDIS_URL", "redis://127.0.0.1:6379/0")
    anthropic = Anthropic(api_key=api_key)
    r = redis.from_url(redis_url)
    try:
        with psycopg.connect(dsn_from_env()) as conn:
            raw = retrieve_and_generate(
                question,
                tenant_id,
                anthropic=anthropic,
                conn=conn,
                r=r,
            )
    finally:
        r.close()

    citations_raw = raw.get("citations") or []
    if not isinstance(citations_raw, list):
        citations_raw = []
    citations: list[dict[str, object]] = []
    for item in citations_raw[:top_k]:
        if not isinstance(item, dict):
            continue
        citations.append(
            {
                "chunk_id": str(item["chunk_id"]),
                "doc_id": str(item["doc_id"]),
                "score": float(cast(float, item.get("score", 0.0))),
            }
        )

    text = raw.get("text") or raw.get("answer") or ""
    return {
        "answer": str(text),
        "citations": citations,
        "coverage": float(cast(float, raw.get("coverage", 0.0))),
        "rerank_timed_out": bool(raw.get("rerank_timed_out", False)),
    }
