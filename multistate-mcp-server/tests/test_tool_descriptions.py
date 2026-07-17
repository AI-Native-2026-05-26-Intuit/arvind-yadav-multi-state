# multistate-mcp-server/tests/test_tool_descriptions.py
"""Routing-quality gate: every tool description must be actionable."""

from __future__ import annotations

import re

import pytest

from multistate_mcp_server.app import mcp
from multistate_mcp_server.tools import _resources, llm, orders, rag  # noqa: F401

_EXAMPLE_RE = re.compile(r"Example:.+\.\s*$", re.DOTALL)
_EXPECTED = {
    "orders.get_order",
    "orders.create_refund",
    "llm.chat",
    "rag.retrieve_and_generate",
}


@pytest.mark.asyncio
async def test_every_tool_description_passes_routing_quality_gate() -> None:
    tools = await mcp.list_tools()
    names = {t.name for t in tools}
    assert names == _EXPECTED

    for tool in tools:
        desc = tool.description or ""
        assert len(desc) >= 200, f"{tool.name} description too short ({len(desc)})"
        assert "Use this" in desc, f"{tool.name} missing 'Use this'"
        assert "Do NOT" in desc, f"{tool.name} missing 'Do NOT'"
        assert _EXAMPLE_RE.search(desc), f"{tool.name} must end with Example: ..."
