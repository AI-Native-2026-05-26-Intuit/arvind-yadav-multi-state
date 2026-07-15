"""RAGAS golden-set baseline; thresholds tighten over the week."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
from typing import cast

import pytest
from datasets import Dataset
from langchain_anthropic import ChatAnthropic
from langchain_community.embeddings import HuggingFaceEmbeddings
from pydantic import SecretStr

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
# Cheap evaluator for CI budgets; assignment supplies ANTHROPIC_API_KEY via secrets.
_EVAL_MODEL = os.environ.get("MULTISTATE_AI_RAGAS_MODEL", "claude-3-5-haiku-20241022")


def _load_golden_rows() -> list[dict[str, object]]:
    rows = [json.loads(line) for line in GOLDEN.read_text().splitlines() if line]
    if len(rows) < 50:
        pytest.fail(f"golden set has {len(rows)} rows; need >=50")
    return rows


def _load_golden() -> Dataset:
    """Map assignment JSONL fields onto ragas 0.2 SingleTurnSample names."""
    mapped: list[dict[str, object]] = []
    for row in _load_golden_rows():
        mapped.append(
            {
                "user_input": row["question"],
                "response": row["answer"],
                "retrieved_contexts": row["contexts"],
                "reference": row["ground_truth"],
            }
        )
    return Dataset.from_list(mapped)


def _anthropic_llm() -> LangchainLLMWrapper:
    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    )
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY required for RAGAS evaluator")
    chat = ChatAnthropic(  # type: ignore[call-arg]
        model=_EVAL_MODEL,
        api_key=SecretStr(api_key),
        temperature=0,
        max_tokens=1024,
    )
    return LangchainLLMWrapper(chat)


def _local_embeddings() -> LangchainEmbeddingsWrapper:
    # answer_relevancy needs embeddings; reuse MiniLM already pinned by the sidecar
    # so CI does not require OPENAI_API_KEY.
    emb = HuggingFaceEmbeddings(model_name="sentence-transformers/all-MiniLM-L6-v2")
    return LangchainEmbeddingsWrapper(emb)


def _nanmean(values: list[float]) -> float:
    clean = [v for v in values if not math.isnan(v)]
    if not clean:
        return float("nan")
    return sum(clean) / len(clean)


def _run_eval() -> dict[str, float]:
    llm = _anthropic_llm()
    embeddings = _local_embeddings()
    result = evaluate(
        _load_golden(),
        metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
        llm=llm,
        embeddings=embeddings,
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
    if not any(k in scores for k in ("faithfulness", "answer_relevancy")) and isinstance(
        result, dict
    ):
        scores.update({str(k): float(v) for k, v in result.items()})
    return scores


@pytest.mark.slow
def test_ragas_baseline_thresholds() -> None:
    if not os.environ.get("ANTHROPIC_API_KEY") and not os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    ):
        pytest.skip("ANTHROPIC_API_KEY required for RAGAS evaluator")
    if "ANTHROPIC_API_KEY" not in os.environ and os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    ):
        os.environ["ANTHROPIC_API_KEY"] = os.environ["MULTISTATE_AI_ANTHROPIC_API_KEY"]

    scores = _run_eval()
    # Guard against total LLM/metric collapse (all-NaN means miswired columns or auth).
    for metric in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
        n = scores.get(f"{metric}_n", 0.0)
        assert n >= 40, f"{metric} produced too few finite scores: {scores}"

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
    assert any(not cast(list[str], r["contexts"]) for r in rows), (
        "missing-context failure mode absent"
    )
    assert any(
        any("cafeteria" in c or "Wi-Fi" in c for c in cast(list[str], r["contexts"]))
        for r in rows
    ), "junk-context failure mode absent"
    assert any(len(cast(list[str], r["contexts"])) >= 3 for r in rows), (
        "near-duplicate failure mode absent"
    )
