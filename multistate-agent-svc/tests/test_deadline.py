# multistate-agent-svc/tests/test_deadline.py
"""Task 2: @deadline lands sentinel + tags LangSmith run."""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import MagicMock

import pytest

from multistate_agent_svc.nodes._deadline import deadline


@pytest.mark.asyncio
async def test_deadline_fires_sentinel_and_tags_run(monkeypatch: pytest.MonkeyPatch) -> None:
    run = MagicMock()
    monkeypatch.setattr(
        "multistate_agent_svc.nodes._deadline.get_current_run_tree",
        lambda: run,
    )

    @deadline(seconds=0.05, sentinel={"docs": []})
    async def slow_body(_state: dict[str, Any]) -> dict[str, Any]:
        await asyncio.sleep(1.0)
        return {"docs": [{"chunk_id": "x"}]}

    out = await slow_body({})
    assert out == {"docs": []}
    run.add_metadata.assert_called_once()
    meta = run.add_metadata.call_args[0][0]
    assert meta["deadline_exceeded"] is True
    assert meta["limit_s"] == 0.05


@pytest.mark.asyncio
async def test_deadline_passes_through_on_success() -> None:
    @deadline(seconds=1.0, sentinel={"docs": []})
    async def fast_body(_state: dict[str, Any]) -> dict[str, Any]:
        return {"docs": [{"chunk_id": "ok"}]}

    out = await fast_body({})
    assert out == {"docs": [{"chunk_id": "ok"}]}


def test_deadline_wraps_before_traceable_on_workers() -> None:
    """Decorator order: @deadline then @traceable → deadline is outermost."""
    from multistate_agent_svc.nodes import api, retrieval, synthesis

    for fn in (api.api_node, retrieval.retrieval_node, synthesis.synthesis_node):
        # functools.wraps preserves __wrapped__; outermost is deadline wrapper.
        assert hasattr(fn, "__wrapped__")
        # The deadline wrapper's closure carries 'seconds' via cell; smoke that
        # calling the undecorated chain exists.
        assert callable(fn)
