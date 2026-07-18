# multistate-agent-svc/src/multistate_agent_svc/settings.py
"""Pydantic-settings for agent-svc runtime config."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="MULTISTATE_AGENT_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    postgres_url: str = "postgresql://postgres:postgres@localhost:5432/postgres"
    mcp_sse_url: str = "http://127.0.0.1:8090/sse"
    mcp_bearer_jwt: str = ""
    recursion_limit: int = 25
    budget_ceiling_usd_e5: int = 25000
    host: str = "0.0.0.0"
    port: int = 8080
