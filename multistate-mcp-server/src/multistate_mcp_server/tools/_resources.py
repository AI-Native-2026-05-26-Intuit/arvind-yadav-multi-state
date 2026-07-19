# multistate-mcp-server/src/multistate_mcp_server/tools/_resources.py
"""Read-only MCP resources. The W7 D5 agent reads the catalogue at startup
as a fallback when tools/list is unavailable.
"""
from __future__ import annotations

from multistate_mcp_server.app import mcp

_TOOL_CATALOGUE = [
    {
        "name": "orders.get_order",
        "description": "Fetch a single order by id for the caller tenant.",
    },
    {
        "name": "orders.create_refund",
        "description": "Apply an idempotent refund to an existing order.",
    },
    {
        "name": "llm.chat",
        "description": "Forward a chat completion to the W3 D1 llm-proxy.",
    },
    {
        "name": "rag.retrieve_and_generate",
        "description": "Answer a question grounded in the tenant document corpus.",
    },
]


@mcp.resource(uri="multistate://catalogue", name="catalogue")
def catalogue() -> dict[str, object]:
    """Tool catalogue plus short corpus stats (size, tenants)."""
    return {
        "tools": _TOOL_CATALOGUE,
        "corpus": {
            "size": 0,
            "tenants": ["tenant-a", "tenant-b", "tenant-c"],
        },
    }
