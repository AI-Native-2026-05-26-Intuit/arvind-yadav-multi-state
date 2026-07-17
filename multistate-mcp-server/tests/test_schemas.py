# multistate-mcp-server/tests/test_schemas.py
"""Schema contract: additionalProperties:false, Decimal money, UUID, tenant."""

from __future__ import annotations

from decimal import Decimal
from uuid import uuid4

import pytest
from pydantic import ValidationError

from multistate_mcp_server.tools.llm import ChatArgs, ChatMessage
from multistate_mcp_server.tools.orders import CreateRefundArgs, GetOrderArgs
from multistate_mcp_server.tools.rag import RagArgs

_INPUT_MODELS = (GetOrderArgs, CreateRefundArgs, ChatArgs, RagArgs)


def test_every_tool_input_model_forbids_additional_properties() -> None:
    for model in _INPUT_MODELS:
        schema = model.model_json_schema()
        assert schema.get("additionalProperties") is False, model.__name__


def test_create_refund_amount_parses_string_and_rejects_extra_fraction() -> None:
    ok = CreateRefundArgs.model_validate(
        {
            "order_id": "ord-synth-9001",
            "amount": "10.00",
            "reason": "duplicate charge",
            "tenant_id": "tenant-a",
            "idempotency_key": str(uuid4()),
        }
    )
    assert ok.amount == Decimal("10.00")

    with pytest.raises(ValidationError):
        CreateRefundArgs.model_validate(
            {
                "order_id": "ord-synth-9001",
                "amount": "10.001",
                "reason": "duplicate charge",
                "tenant_id": "tenant-a",
                "idempotency_key": str(uuid4()),
            }
        )


def test_create_refund_missing_idempotency_key_raises() -> None:
    with pytest.raises(ValidationError):
        CreateRefundArgs.model_validate(
            {
                "order_id": "ord-synth-9001",
                "amount": "10.00",
                "reason": "duplicate charge",
                "tenant_id": "tenant-a",
            }
        )


def test_malformed_tenant_id_rejected_before_http() -> None:
    with pytest.raises(ValidationError):
        GetOrderArgs(order_id="ord-synth-9001", tenant_id="tenant-d")
    with pytest.raises(ValidationError):
        CreateRefundArgs.model_validate(
            {
                "order_id": "ord-synth-9001",
                "amount": "10.00",
                "reason": "duplicate charge",
                "tenant_id": "tenant-d",
                "idempotency_key": str(uuid4()),
            }
        )
    with pytest.raises(ValidationError):
        ChatArgs(
            messages=[ChatMessage(role="user", content="hi")],
            max_tokens=16,
            tenant_id="tenant-d",
        )
    with pytest.raises(ValidationError):
        RagArgs(question="What is nexus?", tenant_id="tenant-d")
