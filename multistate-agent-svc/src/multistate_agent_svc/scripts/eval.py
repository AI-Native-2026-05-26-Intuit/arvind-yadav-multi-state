# multistate-agent-svc/src/multistate_agent_svc/scripts/eval.py
"""Trajectory eval CI gate.

Fails (exit 1) when any of:
  - trajectory match < 0.70
  - RAGAS / local faithfulness < 0.85
  - cost-per-run regression > 15% vs prior evals/last_run.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path


def _ensure_evals_on_path() -> Path:
    # scripts/eval.py → …/multistate-agent-svc/
    root = Path(__file__).resolve().parents[3]
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    return root


async def _amain(gate: bool) -> int:
    _ensure_evals_on_path()
    from evals.trajectory import _LAST_RUN_PATH, run_eval

    prior: dict[str, float] | None = None
    if _LAST_RUN_PATH.exists():
        try:
            raw = json.loads(_LAST_RUN_PATH.read_text(encoding="utf-8"))
            prior = {
                k: float(v) for k, v in raw.items() if isinstance(v, (int, float))
            }
        except (json.JSONDecodeError, OSError, TypeError, ValueError):
            prior = None
    prior_cost = float(prior["cost_per_run"]) if prior and "cost_per_run" in prior else None

    summary = await run_eval(graph=None)
    print(json.dumps(summary, indent=2))

    if not gate:
        return 0

    failures: list[str] = []
    if summary["trajectory_match"] < 0.70:
        failures.append(
            f"trajectory_match {summary['trajectory_match']:.3f} < 0.70"
        )
    if summary["faithfulness"] < 0.85:
        failures.append(f"faithfulness {summary['faithfulness']:.3f} < 0.85")
    if prior_cost is not None and prior_cost > 0:
        cost = float(summary.get("cost_per_run", 0.0))
        regression = (cost - prior_cost) / prior_cost
        if regression > 0.15:
            failures.append(
                f"cost_per_run regression {regression:.1%} > 15% "
                f"(prior={prior_cost}, now={cost})"
            )

    if failures:
        print("GATE FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("GATE OK")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="agent-svc trajectory eval")
    parser.add_argument("--gate", action="store_true", help="enforce CI thresholds")
    args = parser.parse_args(argv)
    return asyncio.run(_amain(gate=args.gate))


if __name__ == "__main__":
    raise SystemExit(main())
