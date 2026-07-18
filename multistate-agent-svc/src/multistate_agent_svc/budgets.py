# multistate-agent-svc/src/multistate_agent_svc/budgets.py
"""Per-request cost ceiling. Cumulative cost_usd_e5 (integer minor
units, W6 D4 money discipline) is checked before every Claude call;
breach raises BudgetExceeded which the FastAPI handler maps to 503.

Belt-and-braces against runaway loops: this is the SLOW budget
(dollars). The recursion_limit on compile is the FAST budget
(turns); together they trap structural loops and expensive-but-
progressing runs.
"""

from __future__ import annotations

from typing import Any


class BudgetExceeded(Exception):
    """Raised when cumulative spend exceeds the per-request ceiling."""


class BudgetGuard:
    def __init__(self, ceiling_usd_e5: int = 25000) -> None:
        self._ceiling = int(ceiling_usd_e5)
        self._spent = 0

    @property
    def spent_usd_e5(self) -> int:
        return self._spent

    def check_or_raise(self) -> None:
        if self._spent >= self._ceiling:
            raise BudgetExceeded(
                f"spent={self._spent} >= ceiling={self._ceiling} (1e-5 USD)"
            )

    def record_call(self, resp: Any) -> None:
        # Cost computed from Anthropic usage block; per-tenant rate
        # in the W3 D1 llm-proxy is the source of truth, this is the
        # in-process tally for fast budget enforcement.
        usage = getattr(resp, "usage", None)
        if usage is None:
            return
        in_tok = int(getattr(usage, "input_tokens", 0))
        out_tok = int(getattr(usage, "output_tokens", 0))
        # claude-sonnet-4-5 ~ 300 + 1500 microUSD per 1k tok (illustrative).
        cost_e5 = (in_tok * 300 + out_tok * 1500) // 1000
        self._spent += cost_e5
