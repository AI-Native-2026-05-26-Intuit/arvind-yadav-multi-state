from __future__ import annotations

import pytest
from pydantic import ValidationError

from multistate_ai.settings import MultistateAiSettings


def test_settings_reads_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MULTISTATE_AI_PROXY_BASE_URL", "https://proxy.example.internal")
    monkeypatch.setenv("MULTISTATE_AI_PROXY_API_KEY", "key_synth_abc123")
    monkeypatch.setenv("MULTISTATE_AI_TENANT_ID", "tenant-a")
    settings = MultistateAiSettings()  # type: ignore[call-arg]
    assert settings.tenant_id == "tenant-a"
    assert "key_synth_abc123" not in repr(settings)
    assert settings.proxy_api_key.get_secret_value() == "key_synth_abc123"


def test_settings_is_frozen(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MULTISTATE_AI_PROXY_BASE_URL", "https://proxy.example.internal")
    monkeypatch.setenv("MULTISTATE_AI_PROXY_API_KEY", "key_synth_abc123")
    monkeypatch.setenv("MULTISTATE_AI_TENANT_ID", "tenant-a")
    settings = MultistateAiSettings()  # type: ignore[call-arg]
    with pytest.raises(ValidationError):
        settings.tenant_id = "tenant-other"
