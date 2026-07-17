# multistate-mcp-server/src/multistate_mcp_server/tools/orders.py
"""orders.* tools: typed Pydantic v2 args, Decimal for money,
idempotency key on the write, structured McpError on every failure.
"""

from decimal import Decimal
from uuid import UUID

from langsmith import traceable
from pydantic import BaseModel, ConfigDict, Field, field_validator

from multistate_mcp_server.app import AppCtx, mcp
from multistate_mcp_server.auth import resolve_bearer
from multistate_mcp_server.telemetry import run_logged
from multistate_mcp_server.tools._errors import _map_http

# ---- Input schemas ---------------------------------------------------------


class GetOrderArgs(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    order_id: str = Field(min_length=1, description="Order id, e.g. ord-synth-9001.")
    tenant_id: str = Field(pattern=r"^tenant-[abc]$")


class CreateRefundArgs(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    order_id: str = Field(min_length=1)
    amount: Decimal = Field(
        gt=Decimal("0"),
        description="Refund amount; serialised as string in JSON.",
    )
    reason: str = Field(min_length=4, max_length=200)
    tenant_id: str = Field(pattern=r"^tenant-[abc]$")
    idempotency_key: UUID = Field(description="UUID v4; required so retries are safe.")

    @field_validator("amount")
    @classmethod
    def _at_most_two_decimal_places(cls, value: Decimal) -> Decimal:
        exponent = value.as_tuple().exponent
        if isinstance(exponent, int) and exponent < -2:
            raise ValueError("amount must have at most 2 decimal places")
        return value


# ---- Output schemas (pre-shape; see Likely Sticking Points) ----------------


class OrderView(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    order_id: str
    tenant_id: str
    total: Decimal
    status: str


class RefundView(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    order_id: str
    refund_id: str
    amount: Decimal
    reason: str
    status: str


# ---- Tool handlers ---------------------------------------------------------

_DESC_GET_ORDER = (
    "Fetch a single order by id for the caller tenant. Returns the order "
    "id, tenant id, total (Decimal-as-string), and status. Use this when "
    "the user asks to look up, check, view, or read the state of an "
    "existing order. Do NOT use this to modify the order; for refunds "
    "call orders.create_refund. Example: order_id='ord-synth-9001', "
    "tenant_id='tenant-a' returns the order with status='paid'."
)

_DESC_CREATE_REFUND = (
    "Apply a refund to an existing order. Idempotent: pass the same "
    "idempotency_key (UUID v4) on retries and the server returns the "
    "original outcome without double-debiting. Use this when the user "
    "explicitly asks to refund, credit back, or reverse a charge on an "
    "order; Do NOT use it for partial cancellations or order edits. "
    "Returns the refund id and the original amount and reason. Requires "
    "the caller JWT to carry 'orders.write' scope (verified by multistate-orders.) "
    "Example: order_id='ord-synth-9001', amount='10.00', "
    "reason='duplicate', tenant_id='tenant-a' returns the refund view."
)


@mcp.tool(name="orders.get_order", description=_DESC_GET_ORDER)
@traceable(name="orders.get_order", project_name="multistate-mcp-server")
async def orders_get_order(args: GetOrderArgs) -> dict[str, object]:
    async def _inner() -> dict[str, object]:
        ctx: AppCtx = mcp.get_context().request_context.lifespan_context
        r = await ctx.http.get(
            f"/orders/{args.order_id}",
            headers={
                "Authorization": f"Bearer {resolve_bearer(ctx.settings.bearer_jwt)}",
                "X-Tenant": args.tenant_id,
            },
        )
        if r.status_code != 200:
            raise _map_http(r.status_code, r.text)
        return OrderView.model_validate(r.json()).model_dump(mode="json")

    return await run_logged("orders.get_order", args.tenant_id, _inner)


@mcp.tool(name="orders.create_refund", description=_DESC_CREATE_REFUND)
@traceable(name="orders.create_refund", project_name="multistate-mcp-server")
async def orders_create_refund(args: CreateRefundArgs) -> dict[str, object]:
    async def _inner() -> dict[str, object]:
        ctx: AppCtx = mcp.get_context().request_context.lifespan_context
        payload = {
            "orderId": args.order_id,
            "amount": str(args.amount),
            "reason": args.reason,
            "idempotencyKey": str(args.idempotency_key),
        }
        r = await ctx.http.post(
            f"/orders/{args.order_id}/refunds",
            json=payload,
            headers={
                "Authorization": f"Bearer {resolve_bearer(ctx.settings.bearer_jwt)}",
                "X-Tenant": args.tenant_id,
                "Idempotency-Key": str(args.idempotency_key),
            },
        )
        if r.status_code != 200:
            raise _map_http(r.status_code, r.text)
        return RefundView.model_validate(r.json()).model_dump(mode="json")

    return await run_logged("orders.create_refund", args.tenant_id, _inner)
