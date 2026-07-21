# multistate-agent-svc/src/multistate_agent_svc/sse.py
"""Bridge LangGraph astream_events(version='v2') to the W4 D4 React
useChat data-stream protocol over Server-Sent Events.

Event types emitted to the client:
  0:            text-delta chunks for streaming tokens
  2:            typed data event carrying the FinalAnswer
  3:            error event when the graph raises

The X-LangSmith-Trace-Id header is set on the response so the React
side can render a "view trace" deep-link.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any

from langgraph.errors import GraphRecursionError
from langsmith import traceable

from multistate_agent_svc.budgets import BudgetExceeded, BudgetGuard


@traceable(name="chat_request", project_name="multistate-agent-svc-dev")
async def event_stream(
    graph: Any,
    question: str,
    tenant_id: str,
    thread_id: str,
    *,
    recursion_limit: int = 25,
    mcp_session: Any = None,
    budget_guard: BudgetGuard | None = None,
) -> AsyncIterator[bytes]:
    config = {
        "configurable": {"thread_id": thread_id},
        "recursion_limit": recursion_limit,
    }
    inputs: dict[str, Any] = {
        "question": question,
        "tenant_id": tenant_id,
        "thread_id": thread_id,
        "messages": [],
        "docs": [],
        "tool_results": {},
        "answer": None,
        "cost_usd_e5": 0,
    }
    if mcp_session is not None:
        inputs["__mcp_session"] = mcp_session
    if budget_guard is not None:
        inputs["__budget_guard"] = budget_guard
    try:
        async for ev in graph.astream_events(inputs, config, version="v2"):
            kind = ev.get("event")
            if kind == "on_chat_model_stream":
                chunk = ev.get("data", {}).get("chunk")
                delta = getattr(chunk, "content", None) if chunk is not None else None
                if delta:
                    yield f"0:{json.dumps(delta)}\n".encode()
            elif kind == "on_chain_end" and ev.get("name") == "synthesis_agent":
                output = ev.get("data", {}).get("output") or {}
                final = output.get("answer") if isinstance(output, dict) else None
                if final:
                    try:
                        parsed = json.loads(final) if isinstance(final, str) else final
                    except json.JSONDecodeError:
                        parsed = {"text": final}
                    yield f"2:{json.dumps({'finalAnswer': parsed})}\n".encode()
    except GraphRecursionError as exc:
        yield f"3:{json.dumps({'error': 'recursion_limit', 'detail': str(exc)})}\n".encode()
    except BudgetExceeded as exc:
        yield f"3:{json.dumps({'error': 'budget_exceeded', 'detail': str(exc)})}\n".encode()
