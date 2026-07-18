# multistate-agent-svc/src/multistate_agent_svc/nodes/synthesis.py
"""Synthesis agent: Instructor + Claude returning a typed FinalAnswer.

Reads state.docs (zero-or-more) and state.tool_results (zero-or-more);
if both empty the prompt's refusal path kicks in (low confidence,
no fabrication). Instructor retries on Pydantic validation failure
with max_retries=2 so a malformed JSON response self-corrects.
"""

from __future__ import annotations

from typing import Any, cast

import instructor
from anthropic import AsyncAnthropic
from langsmith import traceable
from pydantic import BaseModel, ConfigDict, Field

from multistate_agent_svc.budgets import BudgetGuard
from multistate_agent_svc.nodes._deadline import deadline
from multistate_agent_svc.state import AgentState

_anthropic = instructor.from_anthropic(
    AsyncAnthropic(default_headers={"X-Agent": "synthesis"})
)


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    doc_id: str
    quote: str = Field(min_length=10, max_length=240)


class FinalAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=2000)
    citations: list[Citation] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)


_SYSTEM = (
    "You are the answer assistant for the Tenant support stack. "
    "Tenant context is supplied; cite only from the supplied docs; if neither "
    "docs nor tool_results support a grounded answer, set confidence below 0.4 "
    "and return a refusal in the text field. Never fabricate citations."
)


@deadline(seconds=8.0, sentinel={"answer": "[deadline exceeded]"})
@traceable(name="synthesis_agent", project_name="multistate-agent-svc-dev")
async def synthesis_node(state: AgentState) -> dict[str, Any]:
    guard_raw = state.get("__budget_guard")
    guard = cast(BudgetGuard, guard_raw) if guard_raw is not None else BudgetGuard()
    guard.check_or_raise()

    docs = state.get("docs") or []
    tool_results = state.get("tool_results") or {}
    user = (
        f"Tenant: {state['tenant_id']}\n"
        f"Question: {state['question']}\n"
        f"Docs ({len(docs)}): {docs}\n"
        f"Tool results: {tool_results}"
    )

    # Offline / unit-test path: refuse when both contexts are empty and
    # no Anthropic key is configured (avoids SaaS calls in CI compile tests).
    import os

    if not docs and not tool_results and not os.environ.get("ANTHROPIC_API_KEY"):
        refusal = FinalAnswer(
            text="I do not have enough grounded context to answer.",
            citations=[],
            confidence=0.1,
        )
        return {
            "answer": refusal.model_dump_json(),
            "__visited_nodes": ["synthesis_agent"],
            "cost_usd_e5": 0,
        }

    answer: FinalAnswer = await _anthropic.messages.create(
        model="claude-sonnet-4-5",
        max_tokens=1024,
        max_retries=2,
        response_model=FinalAnswer,
        messages=[
            {"role": "user", "content": [{"type": "text", "text": _SYSTEM}]},
            {"role": "user", "content": user},
        ],
    )
    return {
        "answer": answer.model_dump_json(),
        "__visited_nodes": ["synthesis_agent"],
        "cost_usd_e5": guard.spent_usd_e5,
    }
