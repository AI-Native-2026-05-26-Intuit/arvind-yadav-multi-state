"""RAGAS faithfulness gate — CI fails below 0.85."""

from __future__ import annotations

import pytest

from multistate_ai.ragas_shims import install_ragas_import_shims

install_ragas_import_shims()

# Reuse the W7 D2 Anthropic + MiniLM evaluator wiring (tests/ is on sys.path).
from test_ragas_thresholds import (  # noqa: E402
    _anthropic_api_key,
    _eval_rows,
    _handle_anthropic_error,
    _load_golden_rows,
    _normalize_anthropic_env,
    _preflight_anthropic,
    _run_eval,
)

FAITHFULNESS_GATE = 0.85


@pytest.mark.slow
def test_ragas_faithfulness_gate() -> None:
    _normalize_anthropic_env()
    if not _anthropic_api_key():
        pytest.skip("ANTHROPIC_API_KEY / MULTISTATE_AI_ANTHROPIC_API_KEY unset")
    try:
        _preflight_anthropic()
    except Exception as exc:
        _handle_anthropic_error(exc, stage="preflight")
        raise

    rows = _eval_rows(_load_golden_rows())
    try:
        scores = _run_eval(rows)
    except Exception as exc:
        _handle_anthropic_error(exc, stage="evaluate")
        raise

    faith = float(scores["faithfulness"])
    if faith < FAITHFULNESS_GATE:
        raise SystemExit(f"RAGAS faithfulness {faith:.3f} below gate {FAITHFULNESS_GATE}")
    # The other three metrics are warn-not-fail per Topic 10.
    assert float(scores["answer_relevancy"]) >= 0.80, scores
    assert float(scores["context_precision"]) >= 0.75, scores
    assert float(scores["context_recall"]) >= 0.80, scores
