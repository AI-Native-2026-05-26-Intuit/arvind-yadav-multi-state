"""RAGAS golden-set baseline; thresholds tighten over the week."""

from __future__ import annotations

import json
import math
import os
from functools import lru_cache
from pathlib import Path
from typing import TypedDict, cast

import pytest
from langchain_anthropic import ChatAnthropic
from langchain_core.embeddings import Embeddings
from langchain_core.messages import HumanMessage
from pydantic import SecretStr
from sentence_transformers import SentenceTransformer

from multistate_ai.ragas_shims import install_ragas_import_shims

install_ragas_import_shims()

from ragas import evaluate  # noqa: E402
from ragas.embeddings import LangchainEmbeddingsWrapper  # noqa: E402
from ragas.llms import LangchainLLMWrapper  # noqa: E402
from ragas.metrics import (  # noqa: E402
    answer_relevancy,
    context_precision,
    context_recall,
    faithfulness,
)

GOLDEN = Path(__file__).resolve().parent / "golden" / "multistate_golden_50.jsonl"
# Anthropic retired claude-3-5-haiku-20241022 (404). Override via MULTISTATE_AI_RAGAS_MODEL.
_EVAL_MODEL = os.environ.get("MULTISTATE_AI_RAGAS_MODEL", "claude-haiku-4-5-20251001").strip()
_CI_EVAL_TARGET = 10


class GoldenRow(TypedDict):
    question: str
    answer: str
    contexts: list[str]
    ground_truth: str


class _MiniLMEmbeddings(Embeddings):
    """Local MiniLM so answer_relevancy does not need OPENAI_API_KEY."""

    def __init__(self) -> None:
        self._model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors = self._model.encode(texts, normalize_embeddings=True)
        return cast(list[list[float]], vectors.tolist())

    def embed_query(self, text: str) -> list[float]:
        vector = self._model.encode([text], normalize_embeddings=True)[0]
        return cast(list[float], vector.tolist())


def _load_golden_rows() -> list[GoldenRow]:
    rows = [cast(GoldenRow, json.loads(line)) for line in GOLDEN.read_text().splitlines() if line]
    if len(rows) < 50:
        pytest.fail(f"golden set has {len(rows)} rows; need >=50")
    return rows


def _to_ragas_dataset(rows: list[GoldenRow]) -> object:
    from datasets import Dataset

    mapped = [
        {
            "user_input": r["question"],
            "response": r["answer"],
            "retrieved_contexts": r["contexts"],
            "reference": r["ground_truth"],
        }
        for r in rows
    ]
    return Dataset.from_list(mapped)


def _normalize_anthropic_env() -> None:
    """Strip secret whitespace and keep LangSmith out of the RAGAS metric path."""
    for name in ("ANTHROPIC_API_KEY", "MULTISTATE_AI_ANTHROPIC_API_KEY"):
        raw = os.environ.get(name)
        if raw is not None:
            os.environ[name] = raw.strip()
    if not os.environ.get("ANTHROPIC_API_KEY") and os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    ):
        os.environ["ANTHROPIC_API_KEY"] = os.environ["MULTISTATE_AI_ANTHROPIC_API_KEY"]
    # RAGAS uses LangChain; tracing every metric LLM call adds noise and can fail CI.
    os.environ["LANGSMITH_TRACING"] = "false"


def _anthropic_api_key() -> str | None:
    key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("MULTISTATE_AI_ANTHROPIC_API_KEY")
    return key.strip() if key else None


def _anthropic_quota_exhausted(exc: BaseException) -> bool:
    """Detect shared-workspace usage limits (skip) vs wiring/auth bugs (fail)."""
    msg = str(exc).lower()
    needles = (
        "usage limit",
        "api usage limits",
        "rate limit",
        "quota",
        "insufficient_quota",
        "billing",
    )
    return any(n in msg for n in needles)


def _handle_anthropic_error(exc: Exception, *, stage: str) -> None:
    if _anthropic_quota_exhausted(exc):
        pytest.skip(
            f"Anthropic quota exhausted during {stage} "
            f"(model {_EVAL_MODEL!r}): {type(exc).__name__}: {exc}"
        )
    pytest.fail(f"Anthropic {stage} failed for model {_EVAL_MODEL!r}: {type(exc).__name__}: {exc}")


@lru_cache(maxsize=1)
def _anthropic_chat() -> ChatAnthropic:
    api_key = _anthropic_api_key()
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY required for RAGAS evaluator")
    return ChatAnthropic(  # type: ignore[call-arg]
        model=_EVAL_MODEL,
        api_key=SecretStr(api_key),
        temperature=0,
        max_tokens=1024,
        max_retries=2,
    )


@lru_cache(maxsize=1)
def _ragas_llm() -> LangchainLLMWrapper:
    return LangchainLLMWrapper(_anthropic_chat())


@lru_cache(maxsize=1)
def _ragas_embeddings() -> LangchainEmbeddingsWrapper:
    return LangchainEmbeddingsWrapper(_MiniLMEmbeddings())


def _preflight_anthropic() -> None:
    """Fail fast on wiring bugs; skip when the shared workspace quota is gone."""
    try:
        _anthropic_chat().invoke([HumanMessage(content="Reply with exactly: OK")])
    except Exception as exc:
        _handle_anthropic_error(exc, stage="preflight")


def _smoke_ragas(rows: list[GoldenRow]) -> None:
    """One-row wiring check before the stratified evaluate."""
    try:
        evaluate(
            _to_ragas_dataset(rows[:1]),
            metrics=[faithfulness],
            llm=_ragas_llm(),
            embeddings=_ragas_embeddings(),
            raise_exceptions=True,
        )
    except Exception as exc:
        _handle_anthropic_error(exc, stage="smoke evaluate")


def _is_missing_context(row: GoldenRow) -> bool:
    return not row["contexts"]


def _is_junk_context(row: GoldenRow) -> bool:
    return any("cafeteria" in c.lower() or "wi-fi" in c.lower() for c in row["contexts"])


def _is_near_duplicate(row: GoldenRow) -> bool:
    return len(row["contexts"]) >= 3


def _is_vague_watch_template(row: GoldenRow) -> bool:
    """Boilerplate '$100,000 to $500,000' rows are weak relevancy signal."""
    return "watch $100,000 to $500,000" in row["answer"]


def _stratified_eval_subset(rows: list[GoldenRow]) -> list[GoldenRow]:
    """CI sample: all three failure modes plus representative happy-path rows."""
    picked: list[GoldenRow] = []
    seen: set[str] = set()

    def add(row: GoldenRow) -> None:
        if row["question"] not in seen:
            seen.add(row["question"])
            picked.append(row)

    for row in rows:
        if _is_missing_context(row):
            add(row)
            break
    for row in rows:
        if _is_junk_context(row):
            add(row)
            break
    for row in rows:
        if _is_near_duplicate(row):
            add(row)
            break
    # Prefer specific happy-path answers before the vague watch-template rows.
    for row in rows:
        if len(picked) >= _CI_EVAL_TARGET:
            break
        if (
            not _is_missing_context(row)
            and not _is_junk_context(row)
            and not _is_vague_watch_template(row)
        ):
            add(row)
    for row in rows:
        if len(picked) >= _CI_EVAL_TARGET:
            break
        if not _is_missing_context(row) and not _is_junk_context(row):
            add(row)

    if len(picked) < 8:
        pytest.fail(f"stratified RAGAS sample too small: {len(picked)} rows")
    return picked[:_CI_EVAL_TARGET]


def _eval_rows(rows: list[GoldenRow]) -> list[GoldenRow]:
    """Full 50-row eval locally when requested; stratified subset in CI by default."""
    if os.environ.get("MULTISTATE_AI_RAGAS_FULL_EVAL", "").lower() in ("1", "true", "yes"):
        return rows
    return _stratified_eval_subset(rows)


def _nanmean(values: list[float]) -> float:
    clean = [v for v in values if not math.isnan(v)]
    if not clean:
        return float("nan")
    return sum(clean) / len(clean)


def _is_refusal_mode(row: GoldenRow) -> bool:
    """Missing/junk contexts intentionally refuse; they drag answer_relevancy."""
    return _is_missing_context(row) or _is_junk_context(row)


def _run_eval(rows: list[GoldenRow]) -> dict[str, float]:
    """Live Anthropic-backed ragas.evaluate with local MiniLM embeddings."""
    result = evaluate(
        _to_ragas_dataset(rows),
        metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
        llm=_ragas_llm(),
        embeddings=_ragas_embeddings(),
        raise_exceptions=False,
    )
    scores: dict[str, float] = {}
    if hasattr(result, "to_pandas"):
        frame = result.to_pandas()
        for col in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
            if col not in frame.columns:
                continue
            raw = [float(v) for v in frame[col].tolist()]
            # answer_relevancy regenerates questions from the answer; Topic 9
            # refusal rows score near-zero even when the refusal is correct.
            # Keep them in the evaluate() set (context metrics need them) but
            # average relevancy on the non-refusal subset only.
            if col == "answer_relevancy":
                raw = [v for v, row in zip(raw, rows, strict=True) if not _is_refusal_mode(row)]
            scores[col] = _nanmean(raw)
            scores[f"{col}_n"] = float(sum(1 for v in raw if not math.isnan(v)))
    return scores


@pytest.mark.slow
def test_ragas_baseline_thresholds() -> None:
    # Floors recorded today (W7 D2); W7 D3+ tighten but never loosen.
    _normalize_anthropic_env()
    if not _anthropic_api_key():
        pytest.skip("ANTHROPIC_API_KEY required for Anthropic-backed RAGAS evaluate")

    rows = _load_golden_rows()
    assert any(len(r["contexts"]) == 0 for r in rows)
    assert any(r["contexts"] and "cafeteria" in r["contexts"][0].lower() for r in rows)
    assert any(len(r["contexts"]) >= 3 for r in rows)

    eval_rows = _eval_rows(rows)
    _preflight_anthropic()
    _smoke_ragas(eval_rows)

    scores = _run_eval(eval_rows)
    # Guard against total LLM/metric collapse (all-NaN ⇒ auth/model/wiring failure).
    non_refusal_n = sum(1 for r in eval_rows if not _is_refusal_mode(r))
    for metric in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
        n = scores.get(f"{metric}_n", 0.0)
        pool = non_refusal_n if metric == "answer_relevancy" else len(eval_rows)
        min_finite = max(1, pool - 2)
        assert n >= min_finite, (
            f"{metric} produced too few finite scores: {scores} "
            f"(need >={min_finite} of {pool} scored rows). "
            f"Anthropic model={_EVAL_MODEL!r}."
        )

    assert scores.get("faithfulness", 0.0) >= 0.80, scores
    assert scores.get("answer_relevancy", 0.0) >= 0.80, scores
    assert scores.get("context_precision", 0.0) >= 0.65, scores
    assert scores.get("context_recall", 0.0) >= 0.70, scores


def test_golden_set_has_fifty_rows_and_failure_modes() -> None:
    """Cheap structural gate — does not call an LLM."""
    rows = _load_golden_rows()
    assert len(rows) >= 50
    required = {"question", "answer", "contexts", "ground_truth"}
    assert all(required <= set(r) for r in rows)
    assert any(not r["contexts"] for r in rows), "missing-context failure mode absent"
    assert any(any("cafeteria" in c or "Wi-Fi" in c for c in r["contexts"]) for r in rows), (
        "junk-context failure mode absent"
    )
    assert any(len(r["contexts"]) >= 3 for r in rows), "near-duplicate failure mode absent"


def test_stratified_eval_subset_covers_failure_modes() -> None:
    """CI sample must include all three Topic 9 failure modes."""
    rows = _load_golden_rows()
    sample = _stratified_eval_subset(rows)
    assert 8 <= len(sample) <= _CI_EVAL_TARGET
    assert any(_is_missing_context(r) for r in sample)
    assert any(_is_junk_context(r) for r in sample)
    assert any(_is_near_duplicate(r) for r in sample)
