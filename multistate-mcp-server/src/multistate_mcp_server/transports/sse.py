# multistate-mcp-server/src/multistate_mcp_server/transports/sse.py
# HTTP+SSE entry point for the W7 D5 agent and any remote MCP client.
# Bearer JWT is validated against the W3 D1 JWKS (or HS256 secret locally);
# tenant_id lands in a ContextVar for tool handlers.
from __future__ import annotations

import asyncio
import os

import uvicorn
from uvicorn import Config

from multistate_mcp_server.app import mcp
from multistate_mcp_server.auth import JwtAuthMiddleware
from multistate_mcp_server.settings import Settings
from multistate_mcp_server.tools import _resources, llm, orders, rag  # noqa: F401


async def _serve() -> None:
    settings = Settings()
    host = os.environ.get("MULTISTATE_MCP_HOST", settings.host)
    port = int(os.environ.get("MULTISTATE_MCP_PORT", str(settings.port)))
    mcp.settings.host = host
    mcp.settings.port = port

    starlette_app = mcp.sse_app()
    app = JwtAuthMiddleware(starlette_app, settings)

    config = Config(
        app,
        host=host,
        port=port,
        log_level="info",
        access_log=False,
    )
    server = uvicorn.Server(config)
    await server.serve()


def main() -> None:
    asyncio.run(_serve())


if __name__ == "__main__":
    main()
