# multistate-agent-svc/tests/test_recursion_limit.py
"""Task 3: synthetic loop hits GraphRecursionError within 25."""

from __future__ import annotations

import pytest
from langgraph.errors import GraphRecursionError
from langgraph.graph import START, StateGraph
from typing_extensions import TypedDict


class _LoopState(TypedDict):
    n: int


def _bump(state: _LoopState) -> dict[str, int]:
    return {"n": state["n"] + 1}


def test_graph_recursion_limit_25() -> None:
    sg: StateGraph[_LoopState] = StateGraph(_LoopState)
    sg.add_node("loop", _bump)
    sg.add_edge(START, "loop")
    sg.add_edge("loop", "loop")  # intentional cycle
    # Never reaches END — recursion_limit must trip.
    graph = sg.compile()
    with pytest.raises(GraphRecursionError):
        graph.invoke({"n": 0}, config={"recursion_limit": 25})
