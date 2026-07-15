"""Pydantic Settings - 12-factor config for the multistate-api sidecar."""

from __future__ import annotations

from pydantic import Field, HttpUrl, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class MultistateAiSettings(BaseSettings):  # type: ignore[explicit-any]  # Pydantic BaseSettings boundary
    model_config = SettingsConfigDict(
        env_prefix="MULTISTATE_AI_",
        env_file=".env",
        env_file_encoding="utf-8",
        secrets_dir="/run/secrets",
        extra="forbid",
        frozen=True,
    )

    proxy_base_url: HttpUrl
    proxy_api_key: SecretStr
    proxy_timeout_seconds: float = Field(default=30.0, ge=1.0, le=300.0)
    proxy_max_retries: int = Field(default=3, ge=0, le=10)
    model_id: str = Field(
        default="anthropic.claude-3-5-sonnet-20241022-v2:0",
        min_length=1,
        max_length=128,
    )
    tenant_id: str = Field(min_length=1)
    log_level: str = Field(default="INFO", pattern="^(DEBUG|INFO|WARN|ERROR)$")

    # W7 D2 — retrieval / eval credentials (optional locally; CI supplies via secrets.*)
    langsmith_api_key: SecretStr | None = None
    langsmith_project: str = Field(default="multistate-ai-dev", min_length=1)
    pg_dsn: str | None = None
    anthropic_api_key: SecretStr | None = None
