# multistate-mcp-server/src/multistate_mcp_server/settings.py
"""All env-driven config in one place; prefix MULTISTATE_MCP_."""
from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):  # type: ignore[explicit-any]  # Pydantic BaseSettings boundary
    model_config = SettingsConfigDict(
        env_prefix="MULTISTATE_MCP_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    orders_svc_url: str = Field(default="https://multistate-orders.internal")
    llm_proxy_url: str = Field(default="https://llm-proxy.internal")
    langsmith_project: str = Field(default="multistate-mcp-server")
    tool_timeout_default_s: float = Field(default=5)
    tool_timeout_rag_s: float = Field(default=30)
    # The bearer JWT used to call the W3 D1 services. In stdio mode it
    # comes from the Claude Desktop launcher's env; in SSE mode it comes
    # off the incoming Authorization header (see transports/sse.py).
    bearer_jwt: str = Field(default="")
    host: str = Field(default="0.0.0.0")
    port: int = Field(default=8080)
