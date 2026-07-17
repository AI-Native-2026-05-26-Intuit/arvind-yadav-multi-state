"""Fast unit coverage for retrieve_and_generate flag wiring (no SaaS)."""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import MagicMock

import numpy as np
import pytest
from anthropic import Anthropic
from numpy.typing import NDArray

from multistate_ai import rag as rag_mod
from multistate_ai.corpus import EMBEDDING_DIM
from multistate_ai.hybrid import RetrievedChunk
from multistate_ai.rag import retrieve_and_generate


class _FakeEmbed:
    def encode(
        self,
        sentences: list[str],
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float32]:
        del normalize_embeddings, convert_to_numpy
        return np.zeros((len(sentences), EMBEDDING_DIM), dtype=np.float32)


def _boom(label: str) -> object:
    def _inner(*_a: object, **_k: object) -> object:
        raise AssertionError(label)

    return _inner


@pytest.fixture(autouse=True)
def _stub_embed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(rag_mod, "_embedding_model", lambda: _FakeEmbed())
    monkeypatch.setenv("LANGSMITH_API_KEY", "key_synth_langsmith_test_not_real")
    monkeypatch.setenv("LANGSMITH_TRACING", "false")


def test_retrieve_and_generate_rejects_blank_query() -> None:
    with pytest.raises(ValueError, match="query_text"):
        retrieve_and_generate(
            "   ",
            "tenant-a",
            anthropic=MagicMock(spec=Anthropic),
            conn=MagicMock(),
            r=MagicMock(),
        )


def test_retrieve_and_generate_rejects_blank_tenant() -> None:
    with pytest.raises(ValueError, match="tenant_id"):
        retrieve_and_generate(
            "nexus?",
            "",
            anthropic=MagicMock(spec=Anthropic),
            conn=MagicMock(),
            r=MagicMock(),
        )


def test_retrieve_and_generate_cache_hit_short_circuits(monkeypatch: pytest.MonkeyPatch) -> None:
    cached = {"text": "from-cache", "citations": [], "rerank_timed_out": False}
    monkeypatch.setattr(rag_mod, "cache_lookup", lambda *_a, **_k: cached)
    anthropic = MagicMock(spec=Anthropic)
    out = retrieve_and_generate(
        "nexus?",
        "tenant-a",
        anthropic=anthropic,
        conn=MagicMock(),
        r=MagicMock(),
    )
    assert out["text"] == "from-cache"
    anthropic.messages.create.assert_not_called()


def test_retrieve_and_generate_flags_skip_hybrid_mmr_rerank(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(rag_mod, "cache_lookup", lambda *_a, **_k: None)
    monkeypatch.setattr(rag_mod, "register_vector", lambda _c: None)
    monkeypatch.setattr(
        rag_mod,
        "dense_topk_filtered",
        lambda *_a, **_k: [
            RetrievedChunk("42", "d1", "California nexus threshold text.", 0.1)
        ],
    )
    sparse_calls: list[int] = []

    def _sparse(*_a: object, **_k: object) -> list[RetrievedChunk]:
        sparse_calls.append(1)
        return []

    monkeypatch.setattr(rag_mod, "sparse_topk_fts", _sparse)
    monkeypatch.setattr(rag_mod, "mmr_pick", _boom("mmr"))
    monkeypatch.setattr(rag_mod, "bge_rerank", _boom("rerank"))
    monkeypatch.setattr(rag_mod, "cache_store", lambda *_a, **_k: None)

    msg = SimpleNamespace(content=[SimpleNamespace(text="generated answer")])
    anthropic = MagicMock(spec=Anthropic)
    anthropic.messages.create.return_value = msg
    out = retrieve_and_generate(
        "What is the CA nexus threshold?",
        "tenant-a",
        anthropic=anthropic,
        conn=MagicMock(),
        r=MagicMock(),
        use_hybrid=False,
        use_mmr=False,
        use_rerank=False,
        use_filter=False,
    )
    assert sparse_calls == []
    assert out["text"] == "generated answer"
    assert out["rerank_timed_out"] is False
    cites = out["citations"]
    assert isinstance(cites, list) and cites
    assert isinstance(cites[0], dict)
    assert cites[0]["chunk_id"] == "42"
    assert cites[0]["doc_id"] == "d1"
    assert cites[0]["tenant_id"] == "tenant-a"
