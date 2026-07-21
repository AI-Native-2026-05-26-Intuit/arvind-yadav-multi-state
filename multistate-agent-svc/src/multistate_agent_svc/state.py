# multistate-agent-svc/src/multistate_agent_svc/state.py
"""AgentState: typed slots for the three-node graph.

Each merge-prone slot carries an explicit reducer so parallel
fan-out from the supervisor does not overwrite the other node's
contribution. Terminal scalars (answer) overwrite on purpose.
"""

from __future__ import annotations

import operator
from typing import Annotated, Any, NotRequired, TypedDict

from langgraph.graph.message import add_messages


def _merge_tool_results(old: dict[str, Any], new: dict[str, Any]) -> dict[str, Any]:
    # Last-write-wins per key; keys never disappear so partial-failure
    # on one fan-out leg preserves the other leg's contribution.
    return {**(old or {}), **(new or {})}


class AgentState(TypedDict):
    # Inputs (set once by the FastAPI handler):
    question: str
    tenant_id: str
    thread_id: str

    # Conversation history (appended by add_messages reducer):
    messages: Annotated[list[Any], add_messages]

    # Parallel-fan-out slots (independent reducers to survive partial failure):
    docs: Annotated[list[dict[str, Any]], operator.add]
    tool_results: Annotated[dict[str, Any], _merge_tool_results]

    # Terminal scalar (overwrite once by synthesis):
    answer: str | None

    # Cumulative cost in 1e-5 USD minor units (W6 D4 money discipline):
    cost_usd_e5: Annotated[int, operator.add]

    # Runtime handles injected by FastAPI lifespan (not checkpointed long-term):
    __mcp_session: NotRequired[Any]
    __budget_guard: NotRequired[Any]

    # Trajectory eval: nodes visited (reducer survives parallel fan-out):
    __visited_nodes: NotRequired[Annotated[list[str], operator.add]]
