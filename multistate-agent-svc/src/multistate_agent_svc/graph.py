# multistate-agent-svc/src/multistate_agent_svc/graph.py
"""Three-node StateGraph with supervisor-routed parallel topology.

  START -> supervisor (router fn returning list[Send])
    \\-> retrieval_agent -.|
                          |-> synthesis_agent -> END
    \\-> api_agent      -'

Every node wraps @deadline + @traceable; every invoke/astream pins a
recursion_limit; the AsyncPostgresSaver checkpointer persists state
after every node so cross-pod / cross-restart resume works.

Adaptation vs assignment template: worker nodes are async (MCP +
Anthropic), so we use AsyncPostgresSaver (not the sync PostgresSaver)
and ainvoke/astream_events rather than sync invoke.

The supervisor is the single policy point: keyword routing today;
tenant gates and per-tenant rate limits land here in the next sprint.
"""

from __future__ import annotations

import contextlib
from typing import Any

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from multistate_agent_svc.nodes.api import api_node
from multistate_agent_svc.nodes.retrieval import retrieval_node
from multistate_agent_svc.nodes.synthesis import synthesis_node
from multistate_agent_svc.settings import Settings
from multistate_agent_svc.state import AgentState

# Module-level holder so the checkpointer connection stays open for the
# process lifetime. AsyncPostgresSaver.from_conn_string is an async
# context manager; we enter it once at build time.
_CHECKPOINTER_CM: Any = None
_CHECKPOINTER: AsyncPostgresSaver | None = None


def supervisor(state: AgentState) -> list[Send]:
    """Single policy point. Returns the parallel fan-out plan.

    Keyword routing today. Next sprint: tenant gates + per-tenant rate
    limits land here so every admission decision stays in one place.
    On an empty plan, default to retrieval_agent so the graph never
    returns blind.
    """
    q = state["question"].lower()
    needs_docs = any(t in q for t in ("policy", "docs", "how do i", "rule"))
    needs_api = any(t in q for t in ("order", "refund", "status", "ord-synth-9001"))
    targets: list[Send] = []
    if needs_docs:
        targets.append(Send("retrieval_agent", state))
    if needs_api:
        targets.append(Send("api_agent", state))
    if not targets:  # default: ground in docs rather than answering blind.
        targets.append(Send("retrieval_agent", state))
    return targets


async def build_multistate_agent_graph(settings: Settings) -> Any:
    """Compile the three-node graph with an AsyncPostgresSaver checkpointer."""
    global _CHECKPOINTER_CM, _CHECKPOINTER

    sg: StateGraph[AgentState] = StateGraph(AgentState)
    sg.add_node("retrieval_agent", retrieval_node)
    sg.add_node("api_agent", api_node)
    sg.add_node("synthesis_agent", synthesis_node)

    sg.add_conditional_edges(START, supervisor, ["retrieval_agent", "api_agent"])
    sg.add_edge("retrieval_agent", "synthesis_agent")
    sg.add_edge("api_agent", "synthesis_agent")
    sg.add_edge("synthesis_agent", END)

    # from_conn_string returns an async context manager; keep it entered
    # for the process lifetime so checkpoint writes do not hit a closed conn.
    if _CHECKPOINTER is None:
        _CHECKPOINTER_CM = AsyncPostgresSaver.from_conn_string(settings.postgres_url)
        _CHECKPOINTER = await _CHECKPOINTER_CM.__aenter__()
        await _CHECKPOINTER.setup()  # idempotent schema migration.

    return sg.compile(checkpointer=_CHECKPOINTER)


async def reset_checkpointer_for_tests() -> None:
    """Test helper: drop the process-level checkpointer so a new URL can bind."""
    global _CHECKPOINTER_CM, _CHECKPOINTER
    if _CHECKPOINTER_CM is not None:
        with contextlib.suppress(Exception):
            await _CHECKPOINTER_CM.__aexit__(None, None, None)
    _CHECKPOINTER_CM = None
    _CHECKPOINTER = None
