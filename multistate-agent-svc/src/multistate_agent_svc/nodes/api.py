# multistate-agent-svc/src/multistate_agent_svc/nodes/api.py
"""API agent: MCP catalogue discovery + Anthropic tool-use loop.

The MCP ClientSession is opened once in the FastAPI lifespan and
stashed on app.state; the node reads it off the state slot. Every
write tool stamps a deterministic UUID5 idempotency key derived
from (thread_id, tool_name, args_hash) so retries dedupe end-to-end.
"""

from __future__ import annotations

import json
import uuid
from typing import Any, cast

from anthropic import AsyncAnthropic
from langsmith import traceable

from multistate_agent_svc.budgets import BudgetGuard
from multistate_agent_svc.nodes._deadline import deadline
from multistate_agent_svc.state import AgentState

_anthropic = AsyncAnthropic(default_headers={"X-Agent": "api"})

# Deterministic namespace for UUID5; never re-roll per call so
# replays from a checkpoint produce the same key.
_IDEMPOTENCY_NS = uuid.UUID("12345678-1234-5678-1234-567812345678")


def _idempotency_key(thread_id: str, tool: str, args: dict[str, Any]) -> str:
    payload = f"{thread_id}|{tool}|{json.dumps(args, sort_keys=True)}"
    return str(uuid.uuid5(_IDEMPOTENCY_NS, payload))


def _serialize_tool_content(content: Any) -> Any:
    """Flatten MCP CallToolResult.content into JSON-serializable form."""
    if content is None:
        return None
    if isinstance(content, (str, int, float, bool, dict, list)):
        return content
    if isinstance(content, list):
        return [_serialize_tool_content(c) for c in content]
    text = getattr(content, "text", None)
    if text is not None:
        return str(text)
    return str(content)


@deadline(seconds=5.0, sentinel={"tool_results": {}})
@traceable(name="api_agent", project_name="multistate-agent-svc-dev")
async def api_node(state: AgentState) -> dict[str, Any]:
    session = state.get("__mcp_session")
    guard_raw = state.get("__budget_guard")
    if session is None:
        return {"tool_results": {}, "__visited_nodes": ["api_agent"]}
    guard = cast(BudgetGuard, guard_raw) if guard_raw is not None else BudgetGuard()

    catalogue = await session.list_tools()
    tools_for_claude = [
        {
            "name": t.name,
            "description": t.description or "",
            "input_schema": t.inputSchema,
        }
        for t in catalogue.tools
    ]
    msgs: list[dict[str, Any]] = [{"role": "user", "content": state["question"]}]
    tool_results: dict[str, Any] = {}
    for _ in range(5):  # bounded tool-use loop.
        guard.check_or_raise()
        resp = await _anthropic.messages.create(
            model="claude-sonnet-4-5",
            max_tokens=1024,
            tools=tools_for_claude,
            messages=msgs,
        )
        guard.record_call(resp)
        if resp.stop_reason != "tool_use":
            break
        for block in resp.content:
            if getattr(block, "type", None) != "tool_use":
                continue
            tool_name = str(block.name)
            tool_input = cast(dict[str, Any], block.input or {})
            # MCP ClientSession.call_tool does not accept HTTP headers;
            # pass tenant + idempotency via tool args meta when present,
            # and stamp Idempotency-Key into the args for write tools.
            call_args = dict(tool_input)
            if "Idempotency-Key" not in call_args and tool_name.endswith("create_refund"):
                call_args["idempotency_key"] = _idempotency_key(
                    state["thread_id"], tool_name, tool_input
                )
            if "tenant_id" not in call_args and "tenantId" not in call_args:
                call_args.setdefault("tenant_id", state["tenant_id"])
            result = await session.call_tool(tool_name, call_args)
            tool_results[tool_name] = _serialize_tool_content(result.content)
        msgs.append({"role": "assistant", "content": resp.content})
        # Feed tool results back so Claude can continue / stop.
        tool_result_blocks = []
        for block in resp.content:
            if getattr(block, "type", None) != "tool_use":
                continue
            tool_result_blocks.append(
                {
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": json.dumps(
                        _serialize_tool_content(tool_results.get(str(block.name)))
                    ),
                }
            )
        if tool_result_blocks:
            msgs.append({"role": "user", "content": tool_result_blocks})
    return {
        "tool_results": tool_results,
        "__visited_nodes": ["api_agent"],
        "cost_usd_e5": guard.spent_usd_e5,
    }
