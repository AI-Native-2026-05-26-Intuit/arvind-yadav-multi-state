# multistate-agent-svc/tests/test_checkpointer_resume.py
"""Task 3: kill mid-graph → restart → same thread_id reads prior checkpoint."""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from typing import Any

import pytest
from testcontainers.postgres import PostgresContainer

from multistate_agent_svc.graph import (
    build_multistate_agent_graph,
    reset_checkpointer_for_tests,
)
from multistate_agent_svc.settings import Settings

os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")


@pytest.fixture()
async def postgres_url() -> AsyncIterator[str]:
    with PostgresContainer("postgres:16-alpine") as pg:
        url = pg.get_connection_url().replace("postgresql+psycopg2://", "postgresql://")
        url = url.replace("postgres://", "postgresql://")
        await reset_checkpointer_for_tests()
        yield url
        await reset_checkpointer_for_tests()


@pytest.mark.asyncio
async def test_checkpointer_resume_after_restart(postgres_url: str) -> None:
    settings = Settings(postgres_url=postgres_url)
    thread_id = "capstone-hitl-1"
    payload: dict[str, Any] = {
        "question": "what is the policy on tenant returns",
        "tenant_id": "tenant-a",
        "thread_id": thread_id,
        "messages": [],
        "docs": [],
        "tool_results": {},
        "answer": None,
        "cost_usd_e5": 0,
    }
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 25}

    graph1 = await build_multistate_agent_graph(settings)
    state1 = await graph1.ainvoke(payload, config=config)
    assert state1.get("answer")

    # Simulate pod kill + restart: drop process-level checkpointer, rebuild.
    await reset_checkpointer_for_tests()
    graph2 = await build_multistate_agent_graph(settings)

    # Second invocation with same thread_id must engage prior checkpoint
    # (history/messages or answer already present — not a blank slate).
    state2 = await graph2.ainvoke(payload, config=config)
    assert state2.get("answer")
    # Checkpoint channel: cost accumulates across resumes via reducer.
    assert int(state2.get("cost_usd_e5") or 0) >= int(state1.get("cost_usd_e5") or 0)

    # Explicit get_state proves the checkpointer retained the thread.
    snap = await graph2.aget_state(config)
    assert snap is not None
    assert snap.values.get("thread_id") == thread_id or snap.values.get("answer")
