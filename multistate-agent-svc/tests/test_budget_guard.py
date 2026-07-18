# multistate-agent-svc/tests/test_budget_guard.py
"""Task 3: BudgetGuard raises BudgetExceeded at ceiling 25000."""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from multistate_agent_svc.budgets import BudgetExceeded, BudgetGuard


def test_budget_guard_allows_under_ceiling() -> None:
    guard = BudgetGuard(ceiling_usd_e5=25000)
    guard.record_call(SimpleNamespace(usage=SimpleNamespace(input_tokens=100, output_tokens=50)))
    guard.check_or_raise()  # must not raise
    assert guard.spent_usd_e5 > 0
    assert guard.spent_usd_e5 < 25000


def test_budget_guard_raises_at_exactly_ceiling() -> None:
    guard = BudgetGuard(ceiling_usd_e5=25000)
    # Force spend to ceiling via repeated record_call.
    # cost_e5 = (in*300 + out*1500) // 1000
    # Pick tokens so one call lands exactly on/over ceiling.
    while guard.spent_usd_e5 < 25000:
        remaining = 25000 - guard.spent_usd_e5
        # Use output tokens: each 1000 out_tok → 1500 e5; scale down.
        out_tok = max(1, (remaining * 1000) // 1500)
        guard.record_call(
            SimpleNamespace(usage=SimpleNamespace(input_tokens=0, output_tokens=out_tok))
        )
    with pytest.raises(BudgetExceeded):
        guard.check_or_raise()


def test_budget_guard_spent_is_int_not_float() -> None:
    guard = BudgetGuard()
    guard.record_call(SimpleNamespace(usage=SimpleNamespace(input_tokens=10, output_tokens=10)))
    assert isinstance(guard.spent_usd_e5, int)


def test_no_float_money_in_budgets_source() -> None:
    from pathlib import Path

    src = Path(__file__).resolve().parents[1] / "src/multistate_agent_svc/budgets.py"
    text = src.read_text(encoding="utf-8")
    # Money fields must not be typed as float.
    assert ": float" not in text
    assert "float(" not in text or "float" not in text.split("spent")[0]
