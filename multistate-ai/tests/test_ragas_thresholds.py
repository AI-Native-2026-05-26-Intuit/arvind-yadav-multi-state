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
    """Fail fast with the real Anthropic error instead of a 26-minute all-NaN run."""
    try:
        _anthropic_chat().invoke([HumanMessage(content="Reply with exactly: OK")])
    except Exception as exc:
        pytest.fail(
            f"Anthropic preflight failed for model {_EVAL_MODEL!r}: {type(exc).__name__}: {exc}"
        )


def _smoke_ragas(rows: list[GoldenRow]) -> None:
    """One-row wiring check before the full 50-row evaluate."""
    evaluate(
        _to_ragas_dataset(rows[:1]),
        metrics=[faithfulness],
        llm=_ragas_llm(),
        embeddings=_ragas_embeddings(),
        raise_exceptions=True,
    )


def _nanmean(values: list[float]) -> float:
    clean = [v for v in values if not math.isnan(v)]
    if not clean:
        return float("nan")
    return sum(clean) / len(clean)


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
            if col in frame.columns:
                raw = [float(v) for v in frame[col].tolist()]
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

    _preflight_anthropic()
    _smoke_ragas(rows)

    scores = _run_eval(rows)
    # Guard against total LLM/metric collapse (all-NaN ⇒ auth/model/wiring failure).
    for metric in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
        n = scores.get(f"{metric}_n", 0.0)
        assert n >= 40, (
            f"{metric} produced too few finite scores: {scores}. "
            f"Anthropic model={_EVAL_MODEL!r}; re-run preflight if the key/model changed."
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
