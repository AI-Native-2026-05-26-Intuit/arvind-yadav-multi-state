# multistate-mcp-server/src/multistate_mcp_server/auth.py
"""SSE bearer JWT validation (W3 D1 JWKS) + per-request ContextVars."""

from __future__ import annotations

import json
from contextvars import ContextVar
from typing import cast

import jwt
from jwt import PyJWKClient

from multistate_mcp_server.settings import Settings

tenant_id_ctx: ContextVar[str | None] = ContextVar("mcp_tenant_id", default=None)
bearer_jwt_ctx: ContextVar[str | None] = ContextVar("mcp_bearer_jwt", default=None)

_jwks_clients: dict[str, PyJWKClient] = {}


class JwtAuthError(Exception):
    """Raised when the inbound bearer JWT fails validation (maps to 4030)."""


def resolve_bearer(fallback: str = "") -> str:
    return bearer_jwt_ctx.get() or fallback


def resolve_tenant_id(fallback: str = "") -> str:
    return tenant_id_ctx.get() or fallback


def validate_bearer_jwt(token: str, settings: Settings) -> dict[str, object]:
    """Validate JWT locally (defensive duplicate of the Java resource-server check).

    HS256 path: set MULTISTATE_MCP_JWT_SECRET for local/dev fixtures.
    RS256 path: fetch signing keys from MULTISTATE_MCP_JWKS_URL (W3 D1 IdP).
    """
    if not token.strip():
        raise JwtAuthError("missing bearer token")

    try:
        if settings.jwt_algorithm.startswith("HS"):
            if not settings.jwt_secret:
                raise JwtAuthError("HS* algorithm requires MULTISTATE_MCP_JWT_SECRET")
            claims_obj: object = jwt.decode(
                token,
                settings.jwt_secret,
                algorithms=[settings.jwt_algorithm],
                audience=settings.jwt_audience,
                issuer=settings.jwt_issuer,
                options={"require": ["exp", "iss", "aud"]},
            )
        else:
            jwks_url = settings.jwks_url or (
                f"{settings.jwt_issuer.rstrip('/')}/protocol/openid-connect/certs"
            )
            client = _jwks_clients.get(jwks_url)
            if client is None:
                client = PyJWKClient(jwks_url)
                _jwks_clients[jwks_url] = client
            signing_key = client.get_signing_key_from_jwt(token)
            claims_obj = jwt.decode(
                token,
                signing_key.key,
                algorithms=[settings.jwt_algorithm],
                audience=settings.jwt_audience,
                issuer=settings.jwt_issuer,
                options={"require": ["exp", "iss", "aud"]},
            )
    except JwtAuthError:
        raise
    except jwt.InvalidAudienceError as exc:
        raise JwtAuthError("wrong audience") from exc
    except jwt.PyJWTError as exc:
        raise JwtAuthError(str(exc)) from exc

    if not isinstance(claims_obj, dict):
        raise JwtAuthError("token claims are not an object")
    claims = cast(dict[str, object], claims_obj)

    tenant = claims.get("tenant_id")
    if not isinstance(tenant, str) or not tenant:
        raise JwtAuthError("tenant_id claim required")
    return claims


async def send_mcp_4030(send: object, message: str) -> None:
    """ASGI 403 response carrying mcp_error_code=4030 for curl/Inspector checks."""
    body = json.dumps(
        {"mcp_error_code": 4030, "error": "forbidden", "error_description": message}
    ).encode()
    await send(  # type: ignore[operator]
        {
            "type": "http.response.start",
            "status": 403,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})  # type: ignore[operator]


class JwtAuthMiddleware:
    """Require Authorization: Bearer on /sse and /messages*; set ContextVars."""

    def __init__(self, app: object, settings: Settings) -> None:
        self.app = app
        self.settings = settings

    async def __call__(self, scope: dict[str, object], receive: object, send: object) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)  # type: ignore[operator]
            return

        path = str(scope.get("path") or "")
        if path != "/sse" and not path.startswith("/messages"):
            await self.app(scope, receive, send)  # type: ignore[operator]
            return

        headers = {
            k.decode("latin-1").lower(): v.decode("latin-1")
            for k, v in cast(list[tuple[bytes, bytes]], scope.get("headers") or [])
        }
        auth = headers.get("authorization", "")
        if not auth.lower().startswith("bearer "):
            await send_mcp_4030(send, "missing bearer token")
            return

        token = auth[7:].strip()
        try:
            claims = validate_bearer_jwt(token, self.settings)
            tenant = str(claims["tenant_id"])
        except JwtAuthError as exc:
            await send_mcp_4030(send, str(exc))
            return

        t_token = tenant_id_ctx.set(tenant)
        b_token = bearer_jwt_ctx.set(token)
        try:
            await self.app(scope, receive, send)  # type: ignore[operator]
        finally:
            tenant_id_ctx.reset(t_token)
            bearer_jwt_ctx.reset(b_token)
