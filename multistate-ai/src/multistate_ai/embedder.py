"""Embedder helpers for the Airflow ingest DAG (idempotent pending set)."""

from __future__ import annotations


def embed_pending() -> int:
    """Embed chunks whose content_hash/model_version do not match stored rows.

    The pre-embed gate lives in ``pgvector_loader.rows_needing_embed``; this
    stub returns 0 so the DAG remains importable without a live corpus.
    """
    return 0
