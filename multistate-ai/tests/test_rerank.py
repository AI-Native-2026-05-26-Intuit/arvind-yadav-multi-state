"""MMR diversification + bge-reranker timeout-and-fallback tests."""

from __future__ import annotations

import time
from itertools import pairwise

import numpy as np
import pytest
from numpy.typing import NDArray

from multistate_ai import rerank as rerank_mod
from multistate_ai.hybrid import RetrievedChunk
from multistate_ai.rerank import bge_rerank, mmr_pick


class _FixedEmbedder:
    """Deterministic unit vectors keyed by the candidate text string."""

    def __init__(self, vectors: dict[str, NDArray[np.float32]]) -> None:
        self._vectors = vectors

    def encode(
        self,
        sentences: list[str],
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float32]:
        del normalize_embeddings, convert_to_numpy
        rows = [self._vectors[s] for s in sentences]
        return np.stack(rows, axis=0).astype(np.float32)


class _FakeCrossEncoder:
    """CrossEncoder stand-in: keyword overlap scoring + optional latency."""

    def __init__(self, *, sleep_s: float = 0.0) -> None:
        self.sleep_s = sleep_s
        self.predict_calls = 0

    def predict(self, pairs: list[list[str]]) -> list[float]:
        self.predict_calls += 1
        if self.sleep_s > 0:
            time.sleep(self.sleep_s)
        scores: list[float] = []
        for pair in pairs:
            query, passage = pair[0].lower(), pair[1].lower()
            overlap = sum(1 for tok in query.split() if tok in passage)
            bonus = 10.0 if "500,000" in passage or "$500,000" in passage else 0.0
            scores.append(float(overlap) + bonus)
        return scores


def _unit(vec: NDArray[np.float64] | NDArray[np.float32]) -> NDArray[np.float32]:
    v = np.asarray(vec, dtype=np.float32)
    n = float(np.linalg.norm(v))
    return (v / n).astype(np.float32) if n > 0 else v


@pytest.fixture(autouse=True)
def _reset_reranker_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(rerank_mod, "_RERANKER", None)
    fake = _FakeCrossEncoder()
    monkeypatch.setattr(rerank_mod, "_get_reranker", lambda: fake)


def test_mmr_lambda_one_matches_cosine_topk() -> None:
    q = _unit(np.array([1.0, 0.0, 0.0], dtype=np.float32))
    texts = {
        "best": _unit(np.array([0.99, 0.10, 0.0], dtype=np.float32)),
        "mid": _unit(np.array([0.80, 0.40, 0.0], dtype=np.float32)),
        "low": _unit(np.array([0.50, 0.70, 0.0], dtype=np.float32)),
    }
    candidates = [
        RetrievedChunk("c-mid", "doc-mid", "mid", 0.0),
        RetrievedChunk("c-low", "doc-low", "low", 0.0),
        RetrievedChunk("c-best", "doc-best", "best", 0.0),
    ]
    embedder = _FixedEmbedder(texts)
    picked = mmr_pick(q, candidates, embedder, k=3, lambda_param=1.0)
    assert [h.chunk_id for h in picked] == ["c-best", "c-mid", "c-low"]


def test_mmr_lambda_zero_spreads_near_duplicates() -> None:
    q = _unit(np.array([1.0, 0.0], dtype=np.float32))
    near_a = _unit(np.array([0.98, 0.20], dtype=np.float32))
    near_b = _unit(np.array([0.97, 0.22], dtype=np.float32))
    diverse = _unit(np.array([0.10, 0.99], dtype=np.float32))
    assert float(near_a @ near_b) > 0.95
    texts = {"near-a": near_a, "near-b": near_b, "diverse": diverse}
    candidates = [
        RetrievedChunk("a", "doc-a", "near-a", 0.0),
        RetrievedChunk("b", "doc-b", "near-b", 0.0),
        RetrievedChunk("d", "doc-d", "diverse", 0.0),
    ]
    embedder = _FixedEmbedder(texts)
    picked = mmr_pick(q, candidates, embedder, k=3, lambda_param=0.0)
    picked_texts = [h.chunk_text for h in picked]
    for left, right in pairwise(picked_texts):
        sim = float(texts[left] @ texts[right])
        assert sim <= 0.95, (left, right, sim)


def test_bge_rerank_lifts_gold_from_rank_five() -> None:
    query = "What is the California economic nexus sales threshold?"
    gold = RetrievedChunk(
        "gold",
        "doc-gold",
        "California's economic nexus sales threshold is $500,000 in gross receipts.",
        0.1,
    )
    distractors = [
        RetrievedChunk(
            f"d-{i}",
            f"doc-d-{i}",
            f"Unrelated payroll withholding note number {i} about remote workers.",
            0.9 - i * 0.01,
        )
        for i in range(4)
    ]
    candidates = [*distractors, gold]
    assert candidates[4].chunk_id == "gold"
    ranked, timed_out = bge_rerank(query, candidates, top_k=6, timeout_ms=120_000)
    assert timed_out is False
    top_ids = [h.chunk_id for h in ranked[:2]]
    assert "gold" in top_ids, top_ids


def test_bge_rerank_timeout_fallback_sets_flag(monkeypatch: pytest.MonkeyPatch) -> None:
    before = rerank_mod.rerank_timeout_count
    slow = _FakeCrossEncoder(sleep_s=0.05)
    monkeypatch.setattr(rerank_mod, "_get_reranker", lambda: slow)
    candidates = [
        RetrievedChunk("c1", "d1", "California nexus sales threshold overview.", 0.5),
        RetrievedChunk("c2", "d2", "New York payroll withholding checklist.", 0.4),
    ]
    results, timed_out = bge_rerank(
        "economic nexus threshold",
        candidates,
        top_k=2,
        timeout_ms=1,
    )
    assert timed_out is True
    assert results == candidates[:2]
    assert rerank_mod.rerank_timeout_count == before + 1


def test_reranker_loads_once_per_process(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = {"n": 0}

    class _CountingCE(_FakeCrossEncoder):
        def __init__(self) -> None:
            super().__init__()
            calls["n"] += 1

    monkeypatch.setattr(rerank_mod, "_RERANKER", None)

    def _factory() -> _FakeCrossEncoder:
        if rerank_mod._RERANKER is None:
            rerank_mod._RERANKER = _CountingCE()  # type: ignore[assignment]
        return rerank_mod._RERANKER  # type: ignore[return-value]

    monkeypatch.setattr(rerank_mod, "_get_reranker", _factory)
    candidates = [
        RetrievedChunk("c1", "d1", "passage one about nexus", 0.5),
        RetrievedChunk("c2", "d2", "passage two", 0.4),
    ]
    bge_rerank("nexus", candidates, top_k=2, timeout_ms=120_000)
    bge_rerank("nexus", candidates, top_k=2, timeout_ms=120_000)
    assert calls["n"] == 1
