"""Batched pgvector insert with ON CONFLICT idempotency."""

from __future__ import annotations

import os
from collections.abc import Iterable

import psycopg
from pgvector.psycopg import register_vector

from .corpus import CorpusRow

_INSERT_SQL = (
    "INSERT INTO doc_chunks "
    "(doc_id, chunk_idx, chunk_text, embedding, model_version, tenant_id) "
    "VALUES (%s, %s, %s, %s, %s, %s) "
    "ON CONFLICT (doc_id, chunk_idx, model_version) DO UPDATE "
    "SET chunk_text = EXCLUDED.chunk_text, embedding = EXCLUDED.embedding"
)


def load_rows(dsn: str, rows: Iterable[CorpusRow]) -> int:
    """Insert (or update) rows; safe to retry on transient failure."""
    payload = [
        (r.doc_id, r.chunk_idx, r.chunk_text, r.embedding, r.model_version, r.tenant_id)
        for r in rows
    ]
    if not payload:
        return 0
    with psycopg.connect(dsn) as conn:
        register_vector(conn)
        with conn.cursor() as cur:
            cur.executemany(_INSERT_SQL, payload)
        conn.commit()
    return len(payload)


def dsn_from_env() -> str:
    """Read PG DSN from env so secrets never appear in source."""
    try:
        return os.environ["MULTISTATE_AI_PG_DSN"]
    except KeyError as exc:
        raise RuntimeError("MULTISTATE_AI_PG_DSN must be set in env") from exc
