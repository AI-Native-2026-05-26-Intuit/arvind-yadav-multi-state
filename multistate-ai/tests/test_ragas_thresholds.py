"""RAGAS golden-set baseline; thresholds tighten over the week."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
from datasets import Dataset
from langchain_anthropic import ChatAnthropic
from langchain_community.embeddings import HuggingFaceEmbeddings

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
_EVAL_MODEL = os.environ.get("MULTISTATE_AI_RAGAS_MODEL", "claude-3-5-haiku-latest")


def _load_golden() -> Dataset:
    rows = [json.loads(line) for line in GOLDEN.read_text().splitlines() if line]
    if len(rows) < 50:
        pytest.fail(f"golden set has {len(rows)} rows; need >=50")
    return Dataset.from_list(rows)


def _anthropic_llm() -> LangchainLLMWrapper:
    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    )
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY required for RAGAS evaluator")
    # ChatAnthropic stubs vary by version; construct via kwargs dict for mypy.
    chat = ChatAnthropic(  # type: ignore[call-arg]
        model_name=_EVAL_MODEL,
        anthropic_api_key=api_key,
        temperature=0,
    )
    return LangchainLLMWrapper(chat)


def _local_embeddings() -> LangchainEmbeddingsWrapper:
    # answer_relevancy needs embeddings; use the same MiniLM already in the sidecar
    # so CI does not require OPENAI_API_KEY.
    emb = HuggingFaceEmbeddings(model_name="sentence-transformers/all-MiniLM-L6-v2")
    return LangchainEmbeddingsWrapper(emb)


def _run_eval() -> dict[str, float]:
    llm = _anthropic_llm()
    embeddings = _local_embeddings()
    result = evaluate(
        _load_golden(),
        metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
        llm=llm,
        embeddings=embeddings,
        column_map={
            "question": "question",
            "answer": "answer",
            "contexts": "contexts",
            "ground_truth": "ground_truth",
        },
    )
    scores: dict[str, float] = {}
    if hasattr(result, "to_pandas"):
        frame = result.to_pandas()
        for col in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
            if col in frame.columns:
                scores[col] = float(frame[col].mean())
    if not scores and isinstance(result, dict):
        scores = {str(k): float(v) for k, v in result.items()}
    if not scores:
        for key in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
            try:
                scores[key] = float(result[key])
            except (KeyError, TypeError, ValueError):
                continue
    return scores


@pytest.mark.slow
def test_ragas_baseline_thresholds() -> None:
    if not os.environ.get("ANTHROPIC_API_KEY") and not os.environ.get(
        "MULTISTATE_AI_ANTHROPIC_API_KEY"
    ):
        pytest.skip("ANTHROPIC_API_KEY required for RAGAS evaluator")
    if "ANTHROPIC_API_KEY" not in os.environ and os.environ.get("MULTISTATE_AI_ANTHROPIC_API_KEY"):
        os.environ["ANTHROPIC_API_KEY"] = os.environ["MULTISTATE_AI_ANTHROPIC_API_KEY"]

    scores = _run_eval()
    assert scores.get("faithfulness", 0.0) >= 0.80, scores
    assert scores.get("answer_relevancy", 0.0) >= 0.80, scores
    assert scores.get("context_precision", 0.0) >= 0.65, scores
    assert scores.get("context_recall", 0.0) >= 0.70, scores


def test_golden_set_has_fifty_rows_and_failure_modes() -> None:
    """Cheap structural gate — does not call an LLM."""
    rows = [json.loads(line) for line in GOLDEN.read_text().splitlines() if line]
    assert len(rows) >= 50
    required = {"question", "answer", "contexts", "ground_truth"}
    assert all(required <= set(r) for r in rows)
    assert any(not r["contexts"] for r in rows), "missing-context failure mode absent"
    assert any(any("cafeteria" in c or "Wi-Fi" in c for c in r["contexts"]) for r in rows), (
        "junk-context failure mode absent"
    )
    assert any(len(r["contexts"]) >= 3 for r in rows), "near-duplicate failure mode absent"
