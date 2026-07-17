# multistate-mcp-server/tests/test_e2e_mcp_to_spring.py
"""E2E: Postgres + Spring Boot orders-svc + MCP server subprocess.

Asserts (a) tools/list returns all 4 tools, (b) tools/call
for orders.get_order returns the seeded synthetic order, (c) orders.create_refund
called twice with the same idempotency_key returns the same refund_id.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from collections.abc import Iterator
from typing import cast
from uuid import uuid4

import httpx
import pytest

pytest.importorskip("testcontainers")
from testcontainers.core.container import DockerContainer
from testcontainers.postgres import PostgresContainer

_REQUIRED_TOOLS = {
    "orders.get_order",
    "orders.create_refund",
    "llm.chat",
    "rag.retrieve_and_generate",
}


def _docker_available() -> bool:
    try:
        r = subprocess.run(
            ["docker", "info"],
            check=False,
            capture_output=True,
            timeout=15,
        )
        return r.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


pytestmark = pytest.mark.skipif(
    not _docker_available(),
    reason="Docker daemon required for Testcontainers E2E",
)


@pytest.fixture(scope="session")
def postgres() -> Iterator[dict[str, str]]:
    try:
        with PostgresContainer("postgres:16-alpine") as pg:
            yield {"url": pg.get_connection_url()}
    except Exception as exc:
        pytest.skip(f"postgres container unavailable: {exc}")


@pytest.fixture(scope="session")
def orders_svc(postgres: dict[str, str]) -> Iterator[str]:
    jdbc = postgres["url"].replace("postgresql+psycopg2://", "jdbc:postgresql://")
    jdbc = jdbc.replace("postgresql://", "jdbc:postgresql://")
    container = (
        DockerContainer("uptimecrew/multistate-orders:w3d1")
        .with_env("SPRING_DATASOURCE_URL", jdbc)
        .with_env("SPRING_DATASOURCE_USERNAME", "test")
        .with_env("SPRING_DATASOURCE_PASSWORD", "test")
        .with_exposed_ports(8080)
    )
    try:
        container.start()
    except Exception as exc:
        pytest.skip(f"orders image unavailable: {exc}")
    host = container.get_container_host_ip()
    port = container.get_exposed_port(8080)
    deadline = time.time() + 90
    base = f"http://{host}:{port}"
    while time.time() < deadline:
        try:
            r = httpx.get(f"{base}/actuator/health", timeout=2.0)
            if r.status_code == 200 and r.json().get("status") == "UP":
                break
        except httpx.HTTPError:
            time.sleep(0.5)
    else:
        container.stop()
        pytest.skip("orders-svc did not become healthy in time")
    try:
        yield base
    finally:
        container.stop()


@pytest.fixture()
def mcp_server(orders_svc: str) -> Iterator[subprocess.Popen[str]]:
    env = os.environ.copy()
    env["MULTISTATE_MCP_ORDERS_SVC_URL"] = orders_svc
    env["MULTISTATE_MCP_BEARER_JWT"] = "test-token-with-orders-write-scope"
    env["LANGSMITH_TRACING"] = "false"
    proc = subprocess.Popen(
        [sys.executable, "-u", "-m", "multistate_mcp_server.transports.stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=sys.stderr,
        env=env,
        text=True,
        bufsize=1,
    )
    assert proc.stdin is not None and proc.stdout is not None
    _rpc(
        proc,
        "initialize",
        {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "e2e", "version": "0.0.1"},
        },
        rid=0,
    )
    proc.stdin.write(
        json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n"
    )
    proc.stdin.flush()
    try:
        yield proc
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def _rpc(
    proc: subprocess.Popen[str],
    method: str,
    params: dict[str, object],
    rid: int,
) -> dict[str, object]:
    assert proc.stdin is not None and proc.stdout is not None
    frame = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    proc.stdin.write(frame + "\n")
    proc.stdin.flush()
    line = proc.stdout.readline()
    assert line, f"no response for {method}"
    parsed = json.loads(line)
    assert isinstance(parsed, dict)
    return cast(dict[str, object], parsed)


def test_tools_list_returns_4_tools(mcp_server: subprocess.Popen[str]) -> None:
    listed = _rpc(mcp_server, "tools/list", {}, rid=1)
    result = cast(dict[str, object], listed["result"])
    tools = cast(list[dict[str, object]], result["tools"])
    names = {str(t["name"]) for t in tools}
    assert names == _REQUIRED_TOOLS


def test_get_order_returns_seeded_synthetic_order(
    mcp_server: subprocess.Popen[str],
) -> None:
    called = _rpc(
        mcp_server,
        "tools/call",
        {
            "name": "orders.get_order",
            "arguments": {
                "args": {"order_id": "ord-synth-9001", "tenant_id": "tenant-a"}
            },
        },
        rid=2,
    )
    assert "result" in called
    result = cast(dict[str, object], called["result"])
    content = cast(list[dict[str, object]], result["content"])
    payload = json.loads(str(content[0]["text"]))
    assert payload["order_id"] == "ord-synth-9001"
    assert payload["tenant_id"] == "tenant-a"


def test_create_refund_is_idempotent(mcp_server: subprocess.Popen[str]) -> None:
    key = str(uuid4())
    args = {
        "order_id": "ord-synth-9001",
        "amount": "10.00",
        "reason": "duplicate",
        "tenant_id": "tenant-a",
        "idempotency_key": key,
    }
    first = _rpc(
        mcp_server,
        "tools/call",
        {"name": "orders.create_refund", "arguments": {"args": args}},
        rid=3,
    )
    second = _rpc(
        mcp_server,
        "tools/call",
        {"name": "orders.create_refund", "arguments": {"args": args}},
        rid=4,
    )
    r1 = cast(dict[str, object], first["result"])
    r2 = cast(dict[str, object], second["result"])
    c1 = cast(list[dict[str, object]], r1["content"])
    c2 = cast(list[dict[str, object]], r2["content"])
    p1 = json.loads(str(c1[0]["text"]))
    p2 = json.loads(str(c2[0]["text"]))
    assert p1["refund_id"] == p2["refund_id"]
