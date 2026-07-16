"""MMR diversification + cross-encoder bge-reranker with strict timeout."""

from __future__ import annotations

import time
from typing import Final, Protocol

import numpy as np
from langsmith import traceable
from langsmith.run_helpers import get_current_run_tree
from numpy.typing import NDArray
from sentence_transformers import CrossEncoder

RERANKER_MODEL: Final = "BAAI/bge-reranker-base"
RERANK_TIMEOUT_MS: Final = 300
MMR_LAMBDA: Final = 0.7

_RERANKER: CrossEncoder | None = None
# SRE-facing counter: increment on soft-timeout fallback so alerts can fire.
rerank_timeout_count: int = 0


class EmbeddingEncoder(Protocol):
    """Minimal encode surface used by mmr_pick (SentenceTransformer-compatible)."""

    def encode(
        self,
        sentences: list[str],
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float32]: ...


def _get_reranker() -> CrossEncoder:
    global _RERANKER
    if _RERANKER is None:
        _RERANKER = CrossEncoder(RERANKER_MODEL, max_length=256)
    return _RERANKER


def _attach_rerank_timeout_span(timed_out: bool) -> None:
    """Publish rerank_timed_out on the active LangSmith run when tracing."""
    run = get_current_run_tree()
    if run is None:
        return
    extra = dict(run.extra or {})
    metadata = dict(extra.get("metadata") or {})
    metadata["rerank_timed_out"] = timed_out
    extra["metadata"] = metadata
    run.extra = extra


@traceable(run_type="chain", name="multistate_ai.mmr_pick")
def mmr_pick(
    query_vec: NDArray[np.float32],
    candidates: list[tuple[str, str, float]],
    embedder: EmbeddingEncoder,
    k: int = 20,
    lambda_param: float = MMR_LAMBDA,
) -> list[tuple[str, str, float]]:
    # Greedy MMR: lambda * sim(q, c) - (1 - lambda) * max sim(c, picked).
    if not candidates:
        return []
    texts = [t for _, t, _ in candidates]
    cand_vecs = embedder.encode(
        texts,
        normalize_embeddings=True,
        convert_to_numpy=True,
    ).astype(np.float32)
    q = query_vec.astype(np.float32)
    sim_to_q = cand_vecs @ q  # cosine on unit vectors == dot product
    picked: list[int] = []
    remaining = list(range(len(candidates)))
    while remaining and len(picked) < k:
        best_idx = -1
        best_score = -1e9
        for i in remaining:
            if not picked:
                score = float(sim_to_q[i])
            else:
                max_sim = float(np.max(cand_vecs[picked] @ cand_vecs[i]))
                score = lambda_param * float(sim_to_q[i]) - (1.0 - lambda_param) * max_sim
            if score > best_score:
                best_score = score
                best_idx = i
        picked.append(best_idx)
        remaining.remove(best_idx)
    return [candidates[i] for i in picked]


@traceable(run_type="chain", name="multistate_ai.bge_rerank")
def bge_rerank(
    query_text: str,
    candidates: list[tuple[str, str, float]],
    top_k: int = 6,
    timeout_ms: int = RERANK_TIMEOUT_MS,
) -> tuple[list[tuple[str, str, float]], bool]:
    # Returns (results, rerank_timed_out). On timeout we fall back to the
    # input order to preserve retrieval-only behaviour; the boolean is
    # logged to LangSmith via a rerank_timeout metric.
    global rerank_timeout_count
    if not candidates:
        _attach_rerank_timeout_span(False)
        return [], False
    started = time.perf_counter()
    reranker = _get_reranker()
    # Token-cap each passage. CrossEncoder stubs type predict() too narrowly
    # for the documented list-of-pairs API — keep a precise ignore.
    pairs = [[query_text, t[:1024]] for _, t, _ in candidates]
    scores = reranker.predict(pairs)  # type: ignore[arg-type]
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    if elapsed_ms > timeout_ms:
        # Soft fail: keep retrieval-only ordering; emit metric upstream.
        rerank_timeout_count += 1
        _attach_rerank_timeout_span(True)
        return candidates[:top_k], True
    ranked = sorted(
        zip(candidates, scores, strict=True),
        key=lambda x: float(x[1]),
        reverse=True,
    )
    _attach_rerank_timeout_span(False)
    return [(cid, txt, float(s)) for (cid, txt, _), s in ranked[:top_k]], False
