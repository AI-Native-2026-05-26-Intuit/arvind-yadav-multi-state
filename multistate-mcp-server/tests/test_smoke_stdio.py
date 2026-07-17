# multistate-mcp-server/tests/test_smoke_stdio.py
"""100-call stdio smoke: JSON-RPC purity + _map_http round-trip."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import cast

import pytest

from multistate_mcp_server.tools._errors import DEFAULT_MCP_CODE, HTTP_TO_MCP, _map_http

HOST, PORT = "127.0.0.1", 18911


class _OrdersHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        if "/orders/ord-synth-9001" in self.path:
            body = json.dumps(
                {
                    "order_id": "ord-synth-9001",
                    "tenant_id": "tenant-a",
                    "total": "42.50",
                    "status": "paid",
                }
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if "/orders/missing" in self.path:
            body = b'{"error":"not found"}'
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(500)
        self.end_headers()


@pytest.fixture(scope="module")
def orders_http() -> Iterator[str]:
    httpd = HTTPServer((HOST, PORT), _OrdersHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://{HOST}:{PORT}"
    httpd.shutdown()


def test_map_http_dict_round_trips() -> None:
    for status, code in HTTP_TO_MCP.items():
        err = _map_http(status, f"body-{status}")
        assert err.error.code == code
        assert f"body-{status}" in err.error.message
    fallback = _map_http(502, "upstream")
    assert fallback.error.code == DEFAULT_MCP_CODE


def test_smoke_stdio_100_calls_jsonrpc_only(orders_http: str) -> None:
    env = os.environ.copy()
    env["MULTISTATE_MCP_ORDERS_SVC_URL"] = orders_http
    env["MULTISTATE_MCP_BEARER_JWT"] = "smoke-token"
    env["LANGSMITH_TRACING"] = "false"
    proc = subprocess.Popen(
        [sys.executable, "-u", "-m", "multistate_mcp_server.transports.stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        text=True,
        bufsize=1,
    )
    assert proc.stdin is not None and proc.stdout is not None and proc.stderr is not None
    stdin = proc.stdin
    stdout = proc.stdout
    stderr = proc.stderr

    def rpc(
        method: str,
        params: dict[str, object] | None,
        rid: int | None,
    ) -> dict[str, object] | None:
        msg: dict[str, object] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if rid is not None:
            msg["id"] = rid
        stdin.write(json.dumps(msg) + "\n")
        stdin.flush()
        if rid is None:
            return None
        raw = stdout.readline()
        assert raw, "expected JSON-RPC line on stdout"
        parsed = json.loads(raw)
        assert isinstance(parsed, dict)
        assert parsed.get("jsonrpc") == "2.0"
        return cast(dict[str, object], parsed)

    try:
        init = rpc(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "smoke", "version": "0.0.1"},
            },
            rid=1,
        )
        assert init is not None and "result" in init
        rpc("notifications/initialized", {}, rid=None)

        for i in range(100):
            listed = rpc("tools/list", {}, rid=1000 + i)
            assert listed is not None and "result" in listed
            result = cast(dict[str, object], listed["result"])
            tools = cast(list[dict[str, object]], result["tools"])
            names = {str(t["name"]) for t in tools}
            assert "orders.get_order" in names

            called = rpc(
                "tools/call",
                {
                    "name": "orders.get_order",
                    "arguments": {
                        "args": {
                            "order_id": "ord-synth-9001",
                            "tenant_id": "tenant-a",
                        }
                    },
                },
                rid=2000 + i,
            )
            assert called is not None and "result" in called
            call_result = cast(dict[str, object], called["result"])
            content = cast(list[dict[str, object]], call_result["content"])
            payload = json.loads(str(content[0]["text"]))
            assert payload["order_id"] == "ord-synth-9001"

        err_call = rpc(
            "tools/call",
            {
                "name": "orders.get_order",
                "arguments": {
                    "args": {"order_id": "missing", "tenant_id": "tenant-a"}
                },
            },
            rid=9999,
        )
        assert err_call is not None
        assert err_call.get("jsonrpc") == "2.0"
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        _ = stderr.read()
