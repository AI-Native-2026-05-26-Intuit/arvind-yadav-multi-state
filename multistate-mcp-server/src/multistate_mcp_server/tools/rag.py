# multistate-mcp-server/src/multistate_mcp_server/tools/rag.py
"""rag.retrieve_and_generate: thin adapter over the W7 D3 pipeline.
The function lives in multistate_ai.rag and is called in-process; the
output is pre-shaped to a small DTO so context-window cost stays bounded.
"""

import asyncio
from collections.abc import Callable
from typing import cast

from langsmith import traceable
from mcp import McpError
from mcp.types import ErrorData
from pydantic import BaseModel, ConfigDict, Field

from multistate_mcp_server.app import AppCtx, mcp
from multistate_mcp_server.dto import RagAnswer
from multistate_mcp_server.tools._errors import RAG_TIMEOUT_MCP_CODE

RagFn = Callable[[str, str, int], dict[str, object]]


class RagArgs(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=2, max_length=2000)
    tenant_id: str = Field(pattern=r"^tenant-[abc]$")
    top_k: int = Field(default=6, ge=1, le=20)


_DESC_RAG = (
    "Answer a question grounded in the tenant's document corpus using "
    "the W7 D3 retrieval pipeline (hybrid dense + sparse, MMR, "
    "cross-encoder rerank). Returns an answer string plus up to top_k "
    "citations with chunk_id, doc_id, and relevance score, plus a "
    "coverage diagnostic and a rerank_timed_out flag. Use this when "
    "the user asks for information that lives in the tenant's documents "
    "(policies, prior filings, indexed knowledge base). Do NOT use this "
    "for transactional reads (use orders.get_order) or generative chat "
    "without grounding (use llm.chat). Default top_k=6; raise to 12-20 "
    "for harder questions where coverage matters. Example: "
    "question='What is the CA nexus threshold?', tenant_id='tenant-a'."
)


def _resolve_rag_fn(ctx: AppCtx) -> RagFn:
    if ctx.rag_fn is None:
        from multistate_mcp_server.rag_adapter import rag_adapter

        ctx.rag_fn = rag_adapter
    return cast(RagFn, ctx.rag_fn)


@mcp.tool(name="rag.retrieve_and_generate", description=_DESC_RAG)
@traceable(name="rag.retrieve_and_generate", project_name="multistate-mcp-server")
async def rag_retrieve_and_generate(args: RagArgs) -> dict[str, object]:
    ctx: AppCtx = mcp.get_context().request_context.lifespan_context
    rag_fn = _resolve_rag_fn(ctx)
    try:
        # Offload the sync RAG call to a worker thread so the event loop
        # is not blocked by the cross-encoder forward pass.
        result_obj: object = await asyncio.wait_for(
            asyncio.to_thread(rag_fn, args.question, args.tenant_id, args.top_k),
            timeout=ctx.settings.tool_timeout_rag_s,
        )
    except TimeoutError as exc:
        raise McpError(
            ErrorData(code=RAG_TIMEOUT_MCP_CODE, message="rag timed out")
        ) from exc

    if not isinstance(result_obj, dict):
        raise McpError(ErrorData(code=5030, message="rag returned non-object"))
    result: dict[str, object] = result_obj

    citations_raw = result.get("citations") or []
    if not isinstance(citations_raw, list):
        citations_raw = []
    answer = RagAnswer.model_validate(
        {
            "answer": str(result.get("answer") or ""),
            "citations": [
                c for c in citations_raw[: args.top_k] if isinstance(c, dict)
            ],
            "coverage": result.get("coverage", 0),
            "rerank_timed_out": bool(result.get("rerank_timed_out", False)),
        }
    )
    return answer.model_dump(mode="json")
