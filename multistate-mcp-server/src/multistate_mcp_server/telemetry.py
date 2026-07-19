# multistate-mcp-server/src/multistate_mcp_server/telemetry.py
"""structlog tool.invoke.start / tool.invoke.end spans (stderr JSON)."""

from __future__ import annotations

import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

import structlog
from mcp import McpError

from multistate_mcp_server.auth import resolve_tenant_id

log = structlog.get_logger("multistate-mcp-server")


@asynccontextmanager
async def tool_span(
    tool: str,
    tenant_id: str,
    *,
    cost_usd_minor: int = 0,
) -> AsyncIterator[dict[str, int | None]]:
    """Emit one start + one end line; mcp_error_code set on McpError."""
    tenant = resolve_tenant_id(tenant_id)
    log.info("tool.invoke.start", tool=tool, tenant_id=tenant)
    started = time.perf_counter()
    state: dict[str, int | None] = {"mcp_error_code": None, "cost_usd_minor": cost_usd_minor}
    try:
        yield state
    except McpError as exc:
        code = getattr(exc.error, "code", None)
        state["mcp_error_code"] = int(code) if code is not None else 5030
        raise
    finally:
        duration_ms = int((time.perf_counter() - started) * 1000)
        log.info(
            "tool.invoke.end",
            tool=tool,
            tenant_id=tenant,
            duration_ms=duration_ms,
            cost_usd_minor=int(state["cost_usd_minor"] or 0),
            mcp_error_code=state["mcp_error_code"],
        )


def estimate_llm_cost_usd_minor(body: dict[str, object]) -> int:
    """W6 D4 money discipline: integer e5 minor units (USD * 1e5)."""
    usage = body.get("usage")
    if not isinstance(usage, dict):
        return 0
    prompt_raw = usage.get("prompt_tokens") or usage.get("input_tokens") or 0
    completion_raw = usage.get("completion_tokens") or usage.get("output_tokens") or 0
    prompt = int(prompt_raw) if isinstance(prompt_raw, (int, str)) else 0
    completion = int(completion_raw) if isinstance(completion_raw, (int, str)) else 0
    total = prompt + completion
    # $0.003 / 1k tokens -> 300 e5 per 1k tokens
    return int(total * 300 // 1000)


async def run_logged[T](
    tool: str,
    tenant_id: str,
    fn: Callable[[], Awaitable[T]],
    *,
    cost_usd_minor: int = 0,
) -> T:
    async with tool_span(tool, tenant_id, cost_usd_minor=cost_usd_minor) as state:
        result = await fn()
        if tool == "llm.chat" and isinstance(result, dict):
            state["cost_usd_minor"] = estimate_llm_cost_usd_minor(result)
        elif tool == "rag.retrieve_and_generate":
            state["cost_usd_minor"] = 50
        return result
