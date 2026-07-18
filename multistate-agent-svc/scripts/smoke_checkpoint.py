# multistate-agent-svc/scripts/smoke_checkpoint.py
"""Task 1 smoke: invoke twice; second call reads prior Postgres checkpoint.

Usage:
  uv run python scripts/smoke_checkpoint.py

Requires MULTISTATE_AGENT_POSTGRES_URL (or defaults to local Postgres).
"""

from __future__ import annotations

import asyncio
import os
import sys

import psycopg

from multistate_agent_svc.graph import (
    build_multistate_agent_graph,
    reset_checkpointer_for_tests,
)
from multistate_agent_svc.settings import Settings


def _count_checkpoints(postgres_url: str, thread_id: str) -> int:
    with psycopg.connect(postgres_url) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM checkpoints WHERE thread_id = %s",
            (thread_id,),
        )
        row = cur.fetchone()
        return int(row[0]) if row else 0


async def _run() -> int:
    settings = Settings()
    await reset_checkpointer_for_tests()
    graph = await build_multistate_agent_graph(settings)
    thread_id = os.environ.get("SMOKE_THREAD_ID", "t1")
    payload = {
        "question": "show order ord-synth-9001",
        "tenant_id": "tenant-a",
        "thread_id": thread_id,
        "messages": [],
        "docs": [],
        "tool_results": {},
        "answer": None,
        "cost_usd_e5": 0,
    }
    config = {
        "configurable": {"thread_id": thread_id},
        "recursion_limit": 25,
    }

    print("ainvoke #1 …")
    state1 = await graph.ainvoke(payload, config=config)
    print("answer:", state1.get("answer"))
    n1 = _count_checkpoints(settings.postgres_url, thread_id)
    print(f"checkpoints after #1: {n1}")

    print("ainvoke #2 …")
    state2 = await graph.ainvoke(payload, config=config)
    print("answer:", state2.get("answer"))
    n2 = _count_checkpoints(settings.postgres_url, thread_id)
    print(f"checkpoints after #2: {n2}")

    if n2 < 2:
        print(
            "FAIL: expected >= 2 checkpoint rows for the same thread_id",
            file=sys.stderr,
        )
        return 1
    print("OK: second invocation engaged the checkpointer")
    return 0


def main() -> int:
    return asyncio.run(_run())


if __name__ == "__main__":
    raise SystemExit(main())
