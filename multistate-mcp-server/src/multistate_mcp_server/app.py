# multistate-mcp-server/src/multistate_mcp_server/app.py
"""FastMCP entry: shared lifespan opens the httpx client and the W7 D3
RAG handle; every tool reads from the lifespan context.
"""
from __future__ import annotations

import logging
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

import httpx
import structlog
from mcp.server.fastmcp import FastMCP

from multistate_mcp_server.settings import Settings

# Logging MUST go to stderr; stdout carries JSON-RPC frames on stdio.
logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(message)s")
structlog.configure(
    processors=[
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer(),
    ],
    logger_factory=structlog.PrintLoggerFactory(file=sys.stderr),
)
log = structlog.get_logger("multistate-mcp-server")


@dataclass
class AppCtx:
    http: httpx.AsyncClient
    rag_fn: object | None
    settings: Settings


@asynccontextmanager
async def lifespan(_: FastMCP[AppCtx]) -> AsyncIterator[AppCtx]:
    s = Settings()
    # Shared HTTP client to the W3 D1 Spring Boot services. The JWT
    # is added per call from the caller context; we keep one pool.
    client = httpx.AsyncClient(
        base_url=s.orders_svc_url,
        timeout=httpx.Timeout(s.tool_timeout_default_s, connect=2.0),
    )
    # Defer multistate_ai.rag import until first RAG tool call — importing it
    # pulls torch/sentence-transformers and would block stdio cold start.
    log.info("lifespan.start", orders_svc=s.orders_svc_url)
    try:
        yield AppCtx(http=client, rag_fn=None, settings=s)
    finally:
        await client.aclose()
        log.info("lifespan.stop")


# Package version lives in pyproject.toml / mcp.json; FastMCP>=1.2 dropped the
# `version=` constructor kwarg (rubric template targeted an older signature).
mcp = FastMCP(name="multistate-mcp-server", lifespan=lifespan)
