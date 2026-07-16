"""Batched pgvector insert with ON CONFLICT idempotency.

W7 D3 adds a content_hash column and a pre-embed gate: skip the model
call when the stored (content_hash, model_version) already matches.
"""

from __future__ import annotations

import hashlib
import os
from collections.abc import Iterable, Sequence

import psycopg
from pgvector.psycopg import register_vector

from .corpus import CorpusRow

_INSERT_SQL = (
    "INSERT INTO doc_chunks "
    "(doc_id, chunk_idx, chunk_text, embedding, model_version, tenant_id, content_hash) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s) "
    "ON CONFLICT (doc_id, chunk_idx, model_version) DO UPDATE "
    "SET chunk_text = EXCLUDED.chunk_text, "
    "    embedding = EXCLUDED.embedding, "
    "    content_hash = EXCLUDED.content_hash"
)

_LOOKUP_SQL = (
    "SELECT doc_id, chunk_idx, content_hash, model_version "
    "FROM doc_chunks "
    "WHERE (doc_id, chunk_idx, model_version) IN ("
    "  SELECT * FROM unnest(%s::text[], %s::int[], %s::text[])"
    ")"
)


def content_hash(text: str) -> str:
    """SHA-256 hex digest of chunk text; stable key for the pre-embed gate."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def rows_needing_embed(
    dsn: str,
    candidates: Sequence[tuple[str, int, str, str]],
) -> list[tuple[str, int, str, str]]:
    """Return candidates whose stored content_hash + model_version do not match.

    Each candidate is ``(doc_id, chunk_idx, chunk_text, model_version)``.
    Matching rows are dropped so the caller can skip the embed model call.
    """
    if not candidates:
        return []
    doc_ids = [c[0] for c in candidates]
    chunk_idxs = [c[1] for c in candidates]
    model_versions = [c[3] for c in candidates]
    incoming_hash = {(c[0], c[1], c[3]): content_hash(c[2]) for c in candidates}

    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        cur.execute(_LOOKUP_SQL, (doc_ids, chunk_idxs, model_versions))
        stored = {
            (str(r[0]), int(r[1]), str(r[3])): (None if r[2] is None else str(r[2]))
            for r in cur.fetchall()
        }

    pending: list[tuple[str, int, str, str]] = []
    for cand in candidates:
        key = (cand[0], cand[1], cand[3])
        if stored.get(key) == incoming_hash[key]:
            continue
        pending.append(cand)
    return pending


def load_rows(dsn: str, rows: Iterable[CorpusRow]) -> int:
    """Insert (or update) rows; safe to retry on transient failure."""
    payload = [
        (
            r.doc_id,
            r.chunk_idx,
            r.chunk_text,
            r.embedding,
            r.model_version,
            r.tenant_id,
            content_hash(r.chunk_text),
        )
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


def upsert_pending() -> int:
    """Upsert embedded chunks awaiting write; DAG-facing stub returns 0."""
    return 0
