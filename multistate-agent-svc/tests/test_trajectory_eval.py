# multistate-agent-svc/tests/test_trajectory_eval.py
"""Must-fix #1: offline eval must not cheat by embedding the golden substring."""

from __future__ import annotations

import pytest

from evals.trajectory import (
    local_faithfulness,
    mock_synthesize,
    planned_nodes,
    run_eval,
    trajectory_match,
)


def test_mock_synthesize_does_not_embed_arbitrary_golden() -> None:
    visited = ("retrieval_agent", "synthesis_agent")
    answer = mock_synthesize(
        "what is the policy on tenant returns",
        "tenant-a",
        visited,
    )
    # Independent output includes the question (so 'returns' can match),
    # but must not be a hard-coded paste of an unseen golden token.
    assert "what is the policy on tenant returns" in answer
    assert "xyzzy-not-in-question" not in answer
    assert local_faithfulness(answer, "returns") == 1.0
    assert local_faithfulness(answer, "xyzzy-not-in-question") == 0.0


def test_local_faithfulness_fails_when_substring_absent() -> None:
    assert local_faithfulness("hello world", "ord-synth-9001") == 0.0


def test_trajectory_match_requires_expected_subset() -> None:
    assert trajectory_match(
        ("retrieval_agent", "api_agent", "synthesis_agent"),
        ("retrieval_agent", "synthesis_agent"),
    ) == 1.0
    assert trajectory_match(("api_agent", "synthesis_agent"), ("retrieval_agent",)) == 0.0


def test_planned_nodes_uses_real_supervisor() -> None:
    assert planned_nodes("show order ord-synth-9001", "tenant-a") == (
        "api_agent",
        "synthesis_agent",
    )


@pytest.mark.asyncio
async def test_run_eval_gate_inputs_are_independent() -> None:
    summary = await run_eval(graph=None)
    assert summary["trajectory_match"] >= 0.70
    assert summary["faithfulness"] >= 0.85
    # Perfect score is allowed only if questions actually contain goldens —
    # the mock never copies expected_answer_substring from the scenario row.
    assert summary["n"] == 20.0
