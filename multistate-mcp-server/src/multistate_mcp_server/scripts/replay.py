# multistate-mcp-server/src/multistate_mcp_server/scripts/replay.py
"""Fixture replay: in-process MCP tool dispatch + p50/p95/p99 latency report."""

from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
import time
from pathlib import Path
from typing import cast
from unittest.mock import MagicMock

import httpx
import respx

from multistate_mcp_server.app import AppCtx, mcp
from multistate_mcp_server.settings import Settings
from multistate_mcp_server.tools import _resources, llm, orders, rag  # noqa: F401


def _percentile(sorted_vals: list[float], pct: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def _load_fixtures(fixtures_dir: Path) -> list[dict[str, object]]:
    files = sorted(fixtures_dir.glob("*.json"))
    if not files:
        raise SystemExit(f"no fixture JSON files in {fixtures_dir}")
    out: list[dict[str, object]] = []
    for path in files:
        # Skip latency baselines / reports committed beside fixtures.
        if path.name.endswith("_baseline.json") or path.name == "replay_baseline.json":
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(payload, list):
            for item in payload:
                if isinstance(item, dict) and "tool" in item:
                    out.append(cast(dict[str, object], item))
        elif isinstance(payload, dict) and "tool" in payload:
            out.append(payload)
        elif isinstance(payload, dict):
            # Not a fixture (e.g. leftover report shape) — ignore.
            continue
        else:
            raise SystemExit(f"fixture {path} must be object or array")
    if not out:
        raise SystemExit(f"no tool fixtures found under {fixtures_dir}")
    return out


def _install_fake_context(ctx: AppCtx) -> None:
    request_context = MagicMock()
    request_context.lifespan_context = ctx
    fake = MagicMock()
    fake.request_context = request_context
    mcp.get_context = MagicMock(return_value=fake)  # type: ignore[method-assign]


async def _invoke(tool: str, arguments: dict[str, object]) -> object:
    return await mcp._tool_manager.call_tool(tool, arguments)


async def _run_replay(
    fixtures_dir: Path,
    report_path: Path,
    baseline_path: Path | None,
    max_regression: float,
) -> int:
    settings = Settings(
        orders_svc_url="http://127.0.0.1:9",
        llm_proxy_url="http://127.0.0.1:9",
        bearer_jwt="replay-token",
    )
    fixtures = _load_fixtures(fixtures_dir)
    latencies: dict[str, list[float]] = {}
    errors: list[str] = []

    def _mock_rag(question: str, tenant_id: str, top_k: int = 6) -> dict[str, object]:
        del question, tenant_id
        return {
            "answer": "replay fixture answer",
            "citations": [
                {"chunk_id": "chunk-1", "doc_id": "doc-1", "score": 0.9},
            ][:top_k],
            "coverage": 1.0,
            "rerank_timed_out": False,
        }

    async with httpx.AsyncClient(
        base_url=settings.orders_svc_url,
        timeout=httpx.Timeout(5.0, connect=2.0),
    ) as http:
        ctx = AppCtx(http=http, rag_fn=_mock_rag, settings=settings)
        _install_fake_context(ctx)

        with respx.mock(assert_all_called=False) as router:
            router.get(url__regex=r".*/orders/.*").respond(
                200,
                json={
                    "order_id": "ord-synth-9001",
                    "tenant_id": "tenant-a",
                    "total": "42.50",
                    "status": "paid",
                },
            )
            router.post(url__regex=r".*/orders/.*/refunds").respond(
                200,
                json={
                    "order_id": "ord-synth-9001",
                    "refund_id": "ref-replay-1",
                    "amount": "10.00",
                    "reason": "duplicate",
                    "status": "applied",
                },
            )
            router.post(url__regex=r".*/v1/chat/completions").respond(
                200,
                json={
                    "id": "chat-replay",
                    "choices": [{"message": {"role": "assistant", "content": "ok"}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5},
                },
            )

            for fixture in fixtures:
                tool = str(fixture["tool"])
                arguments = cast(dict[str, object], fixture.get("arguments") or {})
                repeats_raw = fixture.get("repeats") or 5
                if not isinstance(repeats_raw, (int, str)):
                    repeats_raw = 5
                repeats = int(repeats_raw)
                latencies.setdefault(tool, [])
                for _ in range(repeats):
                    started = time.perf_counter()
                    try:
                        await _invoke(tool, arguments)
                    except Exception as exc:
                        errors.append(f"{tool}: {exc}")
                    else:
                        latencies[tool].append((time.perf_counter() - started) * 1000.0)

    report: dict[str, object] = {"tools": {}, "errors": errors}
    tools_report: dict[str, object] = {}
    for tool, samples in sorted(latencies.items()):
        ordered = sorted(samples)
        tools_report[tool] = {
            "n": len(ordered),
            "p50_ms": round(_percentile(ordered, 50), 3),
            "p95_ms": round(_percentile(ordered, 95), 3),
            "p99_ms": round(_percentile(ordered, 99), 3),
            "mean_ms": round(statistics.fmean(ordered), 3) if ordered else 0.0,
        }
    report["tools"] = tools_report

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if errors:
        print(f"replay FAILED with {len(errors)} errors; wrote {report_path}", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    if baseline_path and baseline_path.exists():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        base_tools = cast(dict[str, object], baseline.get("tools") or {})
        for tool, stats_obj in tools_report.items():
            stats = cast(dict[str, object], stats_obj)
            prev = cast(dict[str, object], base_tools.get(tool) or {})
            prev_raw = prev.get("p95_ms", 0.0)
            cur_raw = stats.get("p95_ms", 0.0)
            prev_p95 = float(prev_raw) if isinstance(prev_raw, (int, float, str)) else 0.0
            cur_p95 = float(cur_raw) if isinstance(cur_raw, (int, float, str)) else 0.0
            if prev_p95 > 0 and cur_p95 > prev_p95 * (1.0 + max_regression):
                print(
                    f"p95 regression {tool}: {cur_p95} > {prev_p95} * "
                    f"{1.0 + max_regression}",
                    file=sys.stderr,
                )
                return 1

    print(json.dumps(report, indent=2))
    print(f"wrote {report_path}", file=sys.stderr)
    return 0


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="MCP tool fixture replay")
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=Path("tests/fixtures"),
        help="directory of fixture JSON files",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path(".replay/latest.json"),
        help="output report path",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=None,
        help="optional previous report for p95 regression gate",
    )
    parser.add_argument(
        "--max-regression",
        type=float,
        default=0.15,
        help="max allowed p95 increase vs baseline (fraction)",
    )
    args = parser.parse_args(argv)
    code = asyncio.run(
        _run_replay(args.fixtures, args.report, args.baseline, args.max_regression)
    )
    raise SystemExit(code)


if __name__ == "__main__":
    main()
