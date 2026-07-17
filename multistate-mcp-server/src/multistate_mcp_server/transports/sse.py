# multistate-mcp-server/src/multistate_mcp_server/transports/sse.py
# HTTP+SSE entry point for the W7 D5 agent and any remote MCP client.
# Bearer JWT / JWKS validation lands in Task 3; Task 1 ships the bind stub.
from __future__ import annotations

import os

from multistate_mcp_server.app import mcp
from multistate_mcp_server.tools import _resources, llm, orders, rag  # noqa: F401


def main() -> None:
    # FastMCP.run() no longer accepts host/port kwargs; configure via settings.
    mcp.settings.host = os.environ.get("MULTISTATE_MCP_HOST", "0.0.0.0")
    mcp.settings.port = int(os.environ.get("MULTISTATE_MCP_PORT", "8080"))
    mcp.run(transport="sse")


if __name__ == "__main__":
    main()
