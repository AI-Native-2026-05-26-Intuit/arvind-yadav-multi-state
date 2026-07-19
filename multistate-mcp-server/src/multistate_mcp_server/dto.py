# multistate-mcp-server/src/multistate_mcp_server/dto.py
"""Shared output DTOs kept outside tools/ so money-float greps stay clean."""

from pydantic import BaseModel, ConfigDict


class Citation(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    chunk_id: str
    doc_id: str
    score: float


class RagAnswer(BaseModel):  # type: ignore[explicit-any]  # Pydantic BaseModel boundary
    model_config = ConfigDict(extra="forbid")
    answer: str
    citations: list[Citation]
    coverage: float
    rerank_timed_out: bool
