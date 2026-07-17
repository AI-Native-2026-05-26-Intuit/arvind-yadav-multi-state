# multistate-mcp-server/src/multistate_mcp_server/transports/stdio.py
"""Stdio entry point used by Claude Desktop. Logging is pinned to stderr
in app.py; stdout carries JSON-RPC frames only.
"""
from __future__ import annotations

from multistate_mcp_server.app import mcp
from multistate_mcp_server.tools import _resources, llm, orders, rag  # noqa: F401


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
