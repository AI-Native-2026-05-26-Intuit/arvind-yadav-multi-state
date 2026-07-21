# multistate-agent-svc/tests/test_synthesis_refusal.py
"""Should-fix #3: empty-context refusal path (confidence < 0.4, no citations)."""

from __future__ import annotations

import json
import os

import pytest

from multistate_agent_svc.nodes.synthesis import synthesis_node
from multistate_agent_svc.state import AgentState


@pytest.mark.asyncio
async def test_synthesis_refusal_when_empty_context_and_no_api_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    # Ensure we do not accidentally pick up a shell-exported key mid-test.
    assert not os.environ.get("ANTHROPIC_API_KEY")

    state: AgentState = {
        "question": "what is the secret sauce",
        "tenant_id": "tenant-a",
        "thread_id": "refusal-1",
        "messages": [],
        "docs": [],
        "tool_results": {},
        "answer": None,
        "cost_usd_e5": 0,
    }
    out = await synthesis_node(state)
    raw = out["answer"]
    assert isinstance(raw, str)
    parsed = json.loads(raw)
    assert parsed["confidence"] < 0.4
    assert parsed["citations"] == []
    assert "grounded context" in parsed["text"].lower() or "refuse" in parsed["text"].lower()
