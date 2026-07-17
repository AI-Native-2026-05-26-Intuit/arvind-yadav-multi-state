# multistate-mcp-server/src/multistate_mcp_server/tools/orders.py
"""orders.* tools: typed Pydantic v2 args, Decimal for money,
idempotency key on the write, structured McpError on every failure.
"""

from decimal import Decimal

from langsmith import traceable
from mcp import McpError
from mcp.types import ErrorData
from pydantic import BaseModel, ConfigDict, Field

from multistate_mcp_server.app import AppCtx, mcp

# ---- Input schemas ---------------------------------------------------------


class GetOrderArgs(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    order_id: str = Field(min_length=1, description="Order id, e.g. ord-synth-9001.")
    tenant_id: str = Field(pattern=r"^tenant-[abc]$")


# ---- Output schemas (pre-shape; see Likely Sticking Points) ----------------


class OrderView(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    order_id: str
    tenant_id: str
    total: Decimal
    status: str


# ---- HTTP-to-McpError mapping (single source of truth) ---------------------


def _map_http(status: int, body: str) -> McpError:
    code = {400: 4001, 401: 4030, 403: 4030, 404: 4040, 409: 4090, 429: 4290}.get(
        status, 5030
    )
    return McpError(ErrorData(code=code, message=body[:200]))


# ---- Tool handlers ---------------------------------------------------------

_DESC_GET_ORDER = (
    "Fetch a single order by id for the caller tenant. Returns the order "
    "id, tenant id, total (Decimal-as-string), and status. Use this when "
    "the user asks to look up, check, view, or read the state of an "
    "existing order. Do NOT use this to modify the order; for refunds "
    "call orders.create_refund. Example: order_id='ord-synth-9001', "
    "tenant_id='tenant-a' returns the order with status='paid'."
)


@mcp.tool(name="orders.get_order", description=_DESC_GET_ORDER)
@traceable(name="orders.get_order", project_name="multistate-mcp-server")
async def orders_get_order(args: GetOrderArgs) -> dict[str, object]:
    ctx: AppCtx = mcp.get_context().request_context.lifespan_context
    r = await ctx.http.get(
        f"/orders/{args.order_id}",
        headers={
            "Authorization": f"Bearer {ctx.settings.bearer_jwt}",
            "X-Tenant": args.tenant_id,
        },
    )
    if r.status_code != 200:
        raise _map_http(r.status_code, r.text)
    return OrderView.model_validate(r.json()).model_dump(mode="json")
