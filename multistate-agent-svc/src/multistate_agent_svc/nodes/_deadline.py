# multistate-agent-svc/src/multistate_agent_svc/nodes/_deadline.py
"""Per-node deadline decorator. Lands a sentinel state slot on
asyncio.TimeoutError and tags the LangSmith run with
deadline_exceeded=True so the partial-failure path is queryable.
"""

from __future__ import annotations

import asyncio
import functools
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from langsmith import get_current_run_tree

F = TypeVar("F", bound=Callable[..., Awaitable[dict[str, Any]]])


def deadline(seconds: float, sentinel: dict[str, Any]) -> Callable[[F], F]:
    def deco(fn: F) -> F:
        @functools.wraps(fn)
        async def wrapper(*args: Any, **kwargs: Any) -> dict[str, Any]:
            try:
                return await asyncio.wait_for(fn(*args, **kwargs), timeout=seconds)
            except TimeoutError:
                run = get_current_run_tree()
                if run is not None:
                    run.add_metadata({"deadline_exceeded": True, "limit_s": seconds})
                return sentinel

        return wrapper  # type: ignore[return-value]

    return deco
