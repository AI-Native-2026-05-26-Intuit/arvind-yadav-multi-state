# multistate-mcp-server/src/multistate_mcp_server/tools/llm.py
"""llm.chat — forwards to the W3 D1 llm-proxy /v1/chat/completions endpoint."""

from langsmith import traceable
from pydantic import BaseModel, ConfigDict, Field

from multistate_mcp_server.app import AppCtx, mcp
from multistate_mcp_server.tools._errors import _map_http


class ChatMessage(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    role: str = Field(min_length=1)
    content: str = Field(min_length=1)


class ChatArgs(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    messages: list[ChatMessage] = Field(min_length=1)
    max_tokens: int = Field(ge=1, le=8192)
    tenant_id: str = Field(pattern=r"^tenant-[abc]$")


_DESC_CHAT = (
    "Forward a chat completion request to the W3 D1 llm-proxy "
    "/v1/chat/completions endpoint. Returns the upstream completion payload. "
    "Use this when the user wants open-ended generative chat that is NOT "
    "grounded in the tenant document corpus and is NOT a transactional "
    "orders read or refund. Do NOT use this for policy/document questions "
    "(use rag.retrieve_and_generate) or order lookups/refunds "
    "(use orders.get_order / orders.create_refund). Upstream HTTP 429 is "
    "mapped to McpError code 4290 so the W7 D5 agent can apply exponential "
    "backoff. Example: messages=[{role:'user', content:'Summarise nexus'}], "
    "max_tokens=256, tenant_id='tenant-a'."
)


@mcp.tool(name="llm.chat", description=_DESC_CHAT)
@traceable(name="llm.chat", project_name="multistate-mcp-server")
async def llm_chat(args: ChatArgs) -> dict[str, object]:
    ctx: AppCtx = mcp.get_context().request_context.lifespan_context
    url = f"{ctx.settings.llm_proxy_url.rstrip('/')}/v1/chat/completions"
    r = await ctx.http.post(
        url,
        json={
            "messages": [m.model_dump(mode="json") for m in args.messages],
            "max_tokens": args.max_tokens,
        },
        headers={
            "Authorization": f"Bearer {ctx.settings.bearer_jwt}",
            "X-Tenant": args.tenant_id,
            "Content-Type": "application/json",
        },
    )
    if r.status_code != 200:
        raise _map_http(r.status_code, r.text)
    body = r.json()
    if not isinstance(body, dict):
        raise _map_http(503, "llm-proxy returned non-object JSON")
    return body
