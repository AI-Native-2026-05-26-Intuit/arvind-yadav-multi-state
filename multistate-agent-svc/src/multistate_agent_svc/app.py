# multistate-agent-svc/src/multistate_agent_svc/app.py
"""FastAPI entrypoint: lifespan opens MCP session + BudgetGuard + compiled graph."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from multistate_agent_svc.budgets import BudgetExceeded, BudgetGuard
from multistate_agent_svc.graph import build_multistate_agent_graph
from multistate_agent_svc.settings import Settings
from multistate_agent_svc.sse import event_stream


class ChatRequest(BaseModel):
    question: str = Field(min_length=1, max_length=4000)
    tenant_id: str = Field(min_length=1, max_length=64)
    thread_id: str = Field(min_length=1, max_length=128)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = Settings()
    app.state.settings = settings
    app.state.graph = await build_multistate_agent_graph(settings)
    app.state.mcp_session = None  # wired when MCP SSE client is available
    yield


app = FastAPI(title="multistate-agent-svc", version="0.1.0", lifespan=lifespan)


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/chat/stream")
async def chat_stream(req: ChatRequest, request: Request) -> StreamingResponse:
    settings: Settings = request.app.state.settings
    graph: Any = request.app.state.graph
    guard = BudgetGuard(ceiling_usd_e5=settings.budget_ceiling_usd_e5)

    async def _gen() -> Any:
        try:
            async for chunk in event_stream(
                graph,
                req.question,
                req.tenant_id,
                req.thread_id,
                recursion_limit=settings.recursion_limit,
                mcp_session=request.app.state.mcp_session,
                budget_guard=guard,
            ):
                yield chunk
        except BudgetExceeded as exc:
            # Mapped to 503 with Retry-After by the outer exception handler
            # when raised before streaming starts; mid-stream yields 3: error.
            yield f'3:{{"error":"budget_exceeded","detail":"{exc}"}}\n'.encode()

    return StreamingResponse(_gen(), media_type="text/event-stream")


@app.exception_handler(BudgetExceeded)
async def budget_exceeded_handler(_request: Request, exc: BudgetExceeded) -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content={"error": "budget_exceeded", "detail": str(exc)},
        headers={"Retry-After": "30"},
    )


def run() -> None:
    import uvicorn

    settings = Settings()
    uvicorn.run(
        "multistate_agent_svc.app:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )


if __name__ == "__main__":
    run()
