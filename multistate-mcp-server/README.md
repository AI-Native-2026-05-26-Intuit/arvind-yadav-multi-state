# multistate-mcp-server

MCP server publishing tools for multistate-orders, llm-proxy, and the W7 D3 RAG pipeline.

## Quick start

```bash
uv sync --system-certs
export MULTISTATE_MCP_ORDERS_SVC_URL=http://127.0.0.1:8080
export MULTISTATE_MCP_BEARER_JWT=...
uv run multistate-mcp-server          # stdio (Claude Desktop)
uv run multistate-mcp-server-sse      # HTTP+SSE (W7 D5)
```

## Inspector

```bash
npx @modelcontextprotocol/inspector uv run python -m multistate_mcp_server.transports.stdio
```

Task 1 surface: `orders.get_order` + resource `multistate://catalogue`.
