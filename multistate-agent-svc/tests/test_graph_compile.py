# multistate-agent-svc/tests/test_graph_compile.py
"""Task 1: graph compiles with three named nodes + PostgresSaver."""

from __future__ import annotations

import operator
import os
from collections.abc import AsyncIterator
from typing import Annotated, get_args, get_origin, get_type_hints

import pytest
from testcontainers.postgres import PostgresContainer

from multistate_agent_svc.graph import (
    build_multistate_agent_graph,
    reset_checkpointer_for_tests,
)
from multistate_agent_svc.settings import Settings
from multistate_agent_svc.state import AgentState, _merge_tool_results

# Ryuk fails under some Docker Desktop / Rancher Desktop setups.
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")


@pytest.fixture()
async def postgres_url() -> AsyncIterator[str]:
    with PostgresContainer("postgres:16-alpine") as pg:
        # psycopg3 wants postgresql:// ; testcontainers may emit postgres://
        url = pg.get_connection_url().replace("postgresql+psycopg2://", "postgresql://")
        url = url.replace("postgres://", "postgresql://")
        await reset_checkpointer_for_tests()
        yield url
        await reset_checkpointer_for_tests()


def test_agent_state_reducers_on_parallel_slots() -> None:
    hints = get_type_hints(AgentState, include_extras=True)
    docs_ann = hints["docs"]
    assert get_origin(docs_ann) is Annotated
    assert operator.add in get_args(docs_ann)

    tools_ann = hints["tool_results"]
    assert get_origin(tools_ann) is Annotated
    assert _merge_tool_results in get_args(tools_ann)


@pytest.mark.asyncio
async def test_graph_compiles_three_nodes_and_checkpointer(postgres_url: str) -> None:
    settings = Settings(postgres_url=postgres_url)
    graph = await build_multistate_agent_graph(settings)

    nodes = set(graph.get_graph().nodes)
    assert "retrieval_agent" in nodes
    assert "api_agent" in nodes
    assert "synthesis_agent" in nodes

    cp = getattr(graph, "checkpointer", None)
    assert cp is not None
    assert type(cp).__name__ == "AsyncPostgresSaver"


@pytest.mark.asyncio
async def test_graph_invoke_returns_answer_field(postgres_url: str) -> None:
    settings = Settings(postgres_url=postgres_url)
    graph = await build_multistate_agent_graph(settings)
    thread_id = "compile-smoke-1"
    result = await graph.ainvoke(
        {
            "question": "what is the policy on tenant returns",
            "tenant_id": "tenant-a",
            "thread_id": thread_id,
            "messages": [],
            "docs": [],
            "tool_results": {},
            "answer": None,
            "cost_usd_e5": 0,
        },
        config={
            "configurable": {"thread_id": thread_id},
            "recursion_limit": 25,
        },
    )
    assert result.get("answer") is not None
    assert isinstance(result["answer"], str)
    assert len(result["answer"]) > 0
