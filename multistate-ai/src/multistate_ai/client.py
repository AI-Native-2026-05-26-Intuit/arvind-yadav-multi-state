"""httpx client for the W3 D1 LLM proxy with retries + structured logging."""

from __future__ import annotations

import logging
from typing import Final

import httpx
from tenacity import (
    retry,
    retry_if_exception,
    stop_after_attempt,
    wait_exponential_jitter,
)

from .models import NexusReviewRequest, NexusReviewResult
from .settings import MultistateAiSettings

_LOG: Final = logging.getLogger("multistate_ai.client")


def _is_retryable(exc: BaseException) -> bool:
    if isinstance(exc, httpx.TimeoutException | httpx.NetworkError):
        return True
    return isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code >= 500


class LlmProxyClient:
    """Synchronous httpx client. async variant lands on W7 D2."""

    def __init__(self, settings: MultistateAiSettings) -> None:
        self._settings = settings
        self._client = httpx.Client(
            base_url=str(settings.proxy_base_url),
            timeout=httpx.Timeout(settings.proxy_timeout_seconds),
            headers={"user-agent": "multistate-ai/0.1.0"},
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> LlmProxyClient:
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    def review(self, request: NexusReviewRequest) -> NexusReviewResult:
        """Single nexus review round-trip; retried per @retry below."""
        return self._call(request)

    @retry(
        reraise=True,
        retry=retry_if_exception(_is_retryable),
        stop=stop_after_attempt(3),
        wait=wait_exponential_jitter(initial=0.5, max=8.0),
    )
    def _call(self, request: NexusReviewRequest) -> NexusReviewResult:
        _LOG.info(
            "proxy.call.start",
            extra={
                "event": "proxy.call.start",
                "correlation_id": request.correlation_id,
                "tenant_id": self._settings.tenant_id,
                "model_id": request.model_id,
            },
        )
        try:
            response = self._client.post(
                "/v1/review",
                content=request.model_dump_json(by_alias=True),
                headers={
                    "content-type": "application/json",
                    "x-correlation-id": request.correlation_id,
                    "authorization": (f"Bearer {self._settings.proxy_api_key.get_secret_value()}"),
                },
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            _LOG.warning(
                "proxy.call.http_status",
                extra={
                    "event": "proxy.call.http_status",
                    "correlation_id": request.correlation_id,
                    "status_code": exc.response.status_code,
                },
            )
            raise
        result = NexusReviewResult.model_validate_json(response.content)
        _LOG.info(
            "proxy.call.ok",
            extra={
                "event": "proxy.call.ok",
                "correlation_id": result.correlation_id,
                "label": result.label,
                "confidence": result.confidence,
            },
        )
        return result
