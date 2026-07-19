# multistate-mcp-server/src/multistate_mcp_server/tools/_errors.py
"""Single HTTP-to-McpError source of truth used by every tool."""

from mcp import McpError
from mcp.types import ErrorData

# Contract dict — Task 4 smoke asserts every row round-trips on the wire.
HTTP_TO_MCP: dict[int, int] = {
    400: 4001,
    401: 4030,
    403: 4030,
    404: 4040,
    409: 4090,
    429: 4290,
}
DEFAULT_MCP_CODE = 5030
RAG_TIMEOUT_MCP_CODE = 5040


def _map_http(status: int, body: str) -> McpError:
    code = HTTP_TO_MCP.get(status, DEFAULT_MCP_CODE)
    return McpError(ErrorData(code=code, message=body[:200]))
