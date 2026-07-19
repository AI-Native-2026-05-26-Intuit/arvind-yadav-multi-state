# multistate-mcp-server/PROMPT_JOURNAL.md

## W7 D4 transcripts

### 1. Tool descriptions for orders.create_refund

**Prompt to Claude:**

> Write a description for an MCP tool `orders.create_refund` that takes
> (order_id, amount Decimal, reason, tenant_id, idempotency_key UUID).
> The description must tell the LLM client WHEN to call this tool, WHEN
> NOT to call it, what scope the caller JWT needs, and one concrete
> example. The tool is idempotent on idempotency_key.

**Raw Claude response (excerpt):**

> Creates a refund for an order. Parameters: order_id, amount, reason,
> tenant_id, idempotency_key.

**Use as is / Modified / Rejected:** Modified — Claude wrote a
generic one-sentence description; I expanded it with the "Use this
when... Do NOT use it for..." routing language from the W7 D4 lesson
sticking-point pattern and appended a concrete Example line so the
description-quality gate passes.

### 2. FastMCP lifespan + httpx client + structured logging

**Prompt to Claude:**

> Scaffold a FastMCP server with an @asynccontextmanager lifespan that
> opens one shared httpx.AsyncClient, configures structlog JSON logging
> to stderr, and registers an orders.get_order tool.

**Raw Claude response (excerpt):**

> Used a sync `httpx.Client` inside the lifespan and logged with the
> default print logger (stdout).

**Use as is / Modified / Rejected:** Modified — switched to
`httpx.AsyncClient`, pinned `logging.basicConfig(stream=sys.stderr)` and
`structlog.PrintLoggerFactory(file=sys.stderr)` so JSON-RPC frames on
stdout stay clean, and deferred the multistate_ai.rag import out of
lifespan startup (torch cold-start).

### 3. Testcontainers E2E + idempotent refund assertion

**Prompt to Claude:**

> Write a pytest Testcontainers E2E that starts postgres:16-alpine and
> uptimecrew/multistate-orders:w3d1, spawns the MCP stdio server, asserts
> tools/list has four tools, orders.get_order returns ord-synth-9001, and
> orders.create_refund called twice with the same idempotency_key returns
> the same refund_id.

**Raw Claude response (excerpt):**

> Produced the fixture + three asserts matching the W7 D4 template,
> including the dual refund call with a shared UUID key.

**Use as is / Modified / Rejected:** Used as is — kept the template
shape; added a Docker-availability skip so local machines without a
daemon fail soft while CI merge-to-main still runs the real gate.
