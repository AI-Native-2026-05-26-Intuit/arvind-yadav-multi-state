"""Unit tests for @traceable retrieve_chunks (no SaaS calls required)."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import psycopg
import pytest
from numpy.typing import NDArray
from testcontainers.postgres import PostgresContainer

from multistate_ai.corpus import EMBEDDING_DIM, MODEL_NAME, embed_dataframe, load_corpus
from multistate_ai.pgvector_loader import load_rows
from multistate_ai.rag import retrieve_chunks, retrieve_chunks_from_env

_ROOT = Path(__file__).resolve().parents[1]
_DDL = _ROOT / "sql" / "V001__doc_chunks.sql"
_SEED = Path(__file__).parent / "fixtures" / "corpus_seed.jsonl"


class _FakeMiniLM:
    def encode(
        self,
        sentences: list[str],
        batch_size: int = 64,
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float64]:
        del batch_size, normalize_embeddings, convert_to_numpy
        rng = np.random.default_rng(11)
        return rng.standard_normal((len(sentences), EMBEDDING_DIM), dtype=np.float64)


def _to_psycopg_dsn(url: str) -> str:
    return url.replace("postgresql+psycopg2://", "postgresql://").replace(
        "postgresql+psycopg://", "postgresql://"
    )


@pytest.fixture(scope="module")
def pg_dsn() -> Iterator[str]:
    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        dsn = _to_psycopg_dsn(pg.get_connection_url())
        with psycopg.connect(dsn) as conn, conn.cursor() as cur:
            cur.execute(_DDL.read_text())
            conn.commit()
        df = load_corpus(_SEED)
        rows = [r for r in embed_dataframe(df, model=_FakeMiniLM()) if r.tenant_id == "tenant-a"]
        load_rows(dsn, rows)
        yield dsn


def test_retrieve_chunks_requires_langsmith_key(
    pg_dsn: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("LANGSMITH_API_KEY", raising=False)
    with pytest.raises(RuntimeError, match="LANGSMITH_API_KEY"):
        retrieve_chunks(pg_dsn, "What is Wayfair economic nexus?")


def test_retrieve_chunks_rejects_invalid_k(pg_dsn: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LANGSMITH_API_KEY", "key_synth_langsmith_test_not_real")
    with pytest.raises(ValueError, match="k must be > 0"):
        retrieve_chunks(pg_dsn, "What is Wayfair economic nexus?", k=0)


def test_retrieve_chunks_rejects_blank_question(
    pg_dsn: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("LANGSMITH_API_KEY", "key_synth_langsmith_test_not_real")
    with pytest.raises(ValueError, match="question must be non-empty"):
        retrieve_chunks(pg_dsn, "   ", k=3)


def test_retrieve_chunks_returns_dict_rows(pg_dsn: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LANGSMITH_API_KEY", "key_synth_langsmith_test_not_real")
    monkeypatch.setenv("LANGSMITH_TRACING", "false")

    fake = _FakeMiniLM()

    with (
        patch("multistate_ai.rag._embedding_model", return_value=fake),
        patch("langsmith.Client", MagicMock()),
    ):
        hits = retrieve_chunks(
            pg_dsn,
            question="California economic nexus dollar floor",
            k=3,
            tenant_id="tenant-a",
            model_version=MODEL_NAME,
        )

    assert len(hits) >= 1
    row = hits[0]
    assert {"doc_id", "chunk_idx", "chunk_text", "distance"} <= set(row)
    assert isinstance(row["chunk_idx"], int)
    assert isinstance(row["distance"], float)


def test_retrieve_chunks_from_env_uses_dsn_from_env(
    pg_dsn: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("LANGSMITH_API_KEY", "key_synth_langsmith_test_not_real")
    monkeypatch.setenv("LANGSMITH_TRACING", "false")
    monkeypatch.setenv("MULTISTATE_AI_PG_DSN", pg_dsn)

    fake = _FakeMiniLM()
    with (
        patch("multistate_ai.rag._embedding_model", return_value=fake),
        patch("langsmith.Client", MagicMock()),
    ):
        hits = retrieve_chunks_from_env(
            question="California economic nexus dollar floor",
            k=2,
            tenant_id="tenant-a",
            model_version=MODEL_NAME,
        )
    assert len(hits) >= 1
