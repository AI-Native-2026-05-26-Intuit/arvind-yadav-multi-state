from __future__ import annotations

import os
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from multistate_ai.models import NexusReviewRequest, Tenant
from multistate_ai.settings import MultistateAiSettings

# Rancher Desktop cannot mount ~/.rd/docker.sock into Ryuk; disable it for local + CI.
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

# ragas still imports ChatVertexAI from langchain_community; the symbol was removed.
from multistate_ai.ragas_shims import install_ragas_import_shims

install_ragas_import_shims()


@pytest.fixture
def settings(monkeypatch: pytest.MonkeyPatch) -> MultistateAiSettings:
    monkeypatch.setenv("MULTISTATE_AI_PROXY_BASE_URL", "https://proxy.test")
    monkeypatch.setenv("MULTISTATE_AI_PROXY_API_KEY", "key_synth_abc123")
    monkeypatch.setenv("MULTISTATE_AI_TENANT_ID", "tenant-a")
    return MultistateAiSettings()  # type: ignore[call-arg]


@pytest.fixture
def sample_request() -> NexusReviewRequest:
    return NexusReviewRequest(
        correlationId="corr-test-001",
        tenant=Tenant(
            id="tenant-001",
            tenantId="tenant-a",
            createdAt=datetime(2026, 1, 15, 12, 0, tzinfo=UTC),
            amount=Decimal("123.45"),
        ),
    )
