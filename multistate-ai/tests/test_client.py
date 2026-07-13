from __future__ import annotations

import logging

import httpx
import pytest
import respx

from multistate_ai.client import LlmProxyClient
from multistate_ai.models import NexusReviewRequest, NexusReviewResult
from multistate_ai.settings import MultistateAiSettings


def _make_review_result(correlation_id: str) -> NexusReviewResult:
    return NexusReviewResult(
        correlationId=correlation_id,
        tenant_id="tenant-a",
        label="standard",
        confidence=0.85,
        rationale="adequate length rationale",
    )


@respx.mock
def test_review_happy_path(
    settings: MultistateAiSettings,
    sample_request: NexusReviewRequest,
    caplog: pytest.LogCaptureFixture,
) -> None:
    correlation_id = sample_request.correlation_id
    result_body = _make_review_result(correlation_id).model_dump_json(by_alias=True)
    route = respx.post("https://proxy.test/v1/review").mock(
        return_value=httpx.Response(200, content=result_body)
    )

    with (
        caplog.at_level(logging.INFO, logger="multistate_ai.client"),
        LlmProxyClient(settings) as client,
    ):
        result = client.review(sample_request)

    assert route.call_count == 1
    assert result.correlation_id == correlation_id
    request = route.calls[0].request
    assert request.headers["x-correlation-id"] == correlation_id
    assert "key_synth_abc123" not in caplog.text
    assert any(
        getattr(record, "correlation_id", None) == correlation_id for record in caplog.records
    )


@respx.mock
def test_review_retries_on_503(
    settings: MultistateAiSettings,
    sample_request: NexusReviewRequest,
) -> None:
    route = respx.post("https://proxy.test/v1/review").mock(
        return_value=httpx.Response(503, text="unavailable")
    )

    with LlmProxyClient(settings) as client, pytest.raises(httpx.HTTPStatusError):
        client.review(sample_request)

    assert route.call_count == 3


@respx.mock
def test_review_no_retry_on_400(
    settings: MultistateAiSettings,
    sample_request: NexusReviewRequest,
) -> None:
    route = respx.post("https://proxy.test/v1/review").mock(
        return_value=httpx.Response(400, text="bad request")
    )

    with LlmProxyClient(settings) as client, pytest.raises(httpx.HTTPStatusError):
        client.review(sample_request)

    assert route.call_count == 1


@respx.mock
def test_review_propagates_correlation_id(
    settings: MultistateAiSettings,
    sample_request: NexusReviewRequest,
) -> None:
    correlation_id = sample_request.correlation_id
    result_body = _make_review_result(correlation_id).model_dump_json(by_alias=True)
    respx.post("https://proxy.test/v1/review").mock(
        return_value=httpx.Response(200, content=result_body)
    )

    with LlmProxyClient(settings) as client:
        result = client.review(sample_request)

    assert result.correlation_id == correlation_id
