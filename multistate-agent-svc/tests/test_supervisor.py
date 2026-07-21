# multistate-agent-svc/tests/test_supervisor.py
"""Task 2: supervisor returns list[Send] for docs/api/both branches."""

from __future__ import annotations

from langgraph.types import Send

from multistate_agent_svc.graph import supervisor
from multistate_agent_svc.state import AgentState


def _base(**overrides: object) -> AgentState:
    state: AgentState = {
        "question": "",
        "tenant_id": "tenant-a",
        "thread_id": "sup-1",
        "messages": [],
        "docs": [],
        "tool_results": {},
        "answer": None,
        "cost_usd_e5": 0,
    }
    state.update(overrides)  # type: ignore[typeddict-item]
    return state


def test_supervisor_docs_only() -> None:
    sends = supervisor(_base(question="what is the policy on tenant returns"))
    assert all(isinstance(s, Send) for s in sends)
    names = [s.node for s in sends]
    assert names == ["retrieval_agent"]


def test_supervisor_api_only() -> None:
    sends = supervisor(_base(question="what is the status of order ord-synth-9001"))
    names = [s.node for s in sends]
    assert names == ["api_agent"]


def test_supervisor_both() -> None:
    sends = supervisor(
        _base(question="look up order ord-synth-9001 and tell me the refund policy")
    )
    names = sorted(s.node for s in sends)
    assert names == ["api_agent", "retrieval_agent"]


def test_supervisor_defaults_to_retrieval_on_empty_plan() -> None:
    sends = supervisor(_base(question="hello there"))
    names = [s.node for s in sends]
    assert names == ["retrieval_agent"]
