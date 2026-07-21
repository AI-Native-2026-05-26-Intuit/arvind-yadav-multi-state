# multistate-agent-svc/src/multistate_agent_svc/nodes/retrieval.py
"""Retrieval agent: wraps W7 D3 retrieve_and_generate via asyncio.to_thread.

Returns top-8 docs pre-shaped to chunk_id / doc_id / score (W7 D4
Section 9 discipline — drop bulky chunk text and embedding metadata).
RERANKER env var picks bge vs Cohere; the D3 pipeline already owns
the BGE path; Cohere is a future swap via the same env flag.
"""

from __future__ import annotations

import asyncio
import os
from typing import Any, cast

from anthropic import Anthropic
from langsmith import traceable

from multistate_agent_svc.budgets import BudgetGuard
from multistate_agent_svc.nodes._deadline import deadline
from multistate_agent_svc.state import AgentState

_anthropic = Anthropic(default_headers={"X-Agent": "retrieval"})


def _reshape_citations(raw: dict[str, Any], top_k: int = 8) -> list[dict[str, Any]]:
    citations_raw = raw.get("citations") or []
    if not isinstance(citations_raw, list):
        return []
    out: list[dict[str, Any]] = []
    for item in citations_raw[:top_k]:
        if not isinstance(item, dict):
            continue
        out.append(
            {
                "chunk_id": str(item.get("chunk_id", "")),
                "doc_id": str(item.get("doc_id", "")),
                "score": float(cast(float, item.get("score", 0.0))),
            }
        )
    return out


def _rewrite_query(question: str) -> str:
    """Inner Claude query-rewrite step before retrieval."""
    try:
        resp = _anthropic.messages.create(
            model="claude-sonnet-4-5",
            max_tokens=128,
            messages=[
                {
                    "role": "user",
                    "content": (
                        "Rewrite the support question into a concise retrieval query. "
                        "Return only the rewritten query text.\n\n"
                        f"Question: {question}"
                    ),
                }
            ],
        )
        text = ""
        if resp.content:
            text = getattr(resp.content[0], "text", "") or ""
        return text.strip() or question
    except Exception:
        return question


def _run_retrieve(question: str, tenant_id: str) -> list[dict[str, Any]]:
    # Prefer the D4 MCP rag adapter shape when available (same reshape).
    # Fall back to calling multistate_ai.rag directly with env DSN/Redis.
    reranker = os.environ.get("RERANKER", "bge-reranker-base")
    _ = reranker  # documented hook; D3 pipeline uses BGE today.

    try:
        from multistate_mcp_server.rag_adapter import rag_adapter

        shaped = rag_adapter(question, tenant_id, top_k=8)
        citations = shaped.get("citations") or []
        if isinstance(citations, list):
            return cast(list[dict[str, Any]], citations[:8])
    except Exception:
        pass

    try:
        import psycopg
        import redis
        from multistate_ai.pgvector_loader import dsn_from_env
        from multistate_ai.rag import retrieve_and_generate

        redis_url = os.environ.get("MULTISTATE_AI_REDIS_URL", "redis://127.0.0.1:6379/0")
        r = redis.from_url(redis_url)
        try:
            with psycopg.connect(dsn_from_env()) as conn:
                raw = retrieve_and_generate(
                    question,
                    tenant_id,
                    anthropic=_anthropic,
                    conn=conn,
                    r=r,
                )
        finally:
            r.close()
        return _reshape_citations(raw, top_k=8)
    except Exception:
        return []


@deadline(seconds=3.0, sentinel={"docs": []})
@traceable(name="retrieval_agent", project_name="multistate-agent-svc-dev")
async def retrieval_node(state: AgentState) -> dict[str, Any]:
    guard_raw = state.get("__budget_guard")
    guard = cast(BudgetGuard, guard_raw) if guard_raw is not None else BudgetGuard()
    guard.check_or_raise()

    rewritten = await asyncio.to_thread(_rewrite_query, state["question"])
    docs = await asyncio.to_thread(_run_retrieve, rewritten, state["tenant_id"])
    return {
        "docs": docs,
        "__visited_nodes": ["retrieval_agent"],
        "cost_usd_e5": guard.spent_usd_e5,
    }
