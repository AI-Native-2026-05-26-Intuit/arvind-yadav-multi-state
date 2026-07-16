"""Testcontainers-backed tests for the idempotent pgvector loader + HNSW."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import numpy as np
import psycopg
import pytest
from numpy.typing import NDArray
from pgvector.psycopg import register_vector
from testcontainers.postgres import PostgresContainer

from multistate_ai.corpus import EMBEDDING_DIM, CorpusRow, embed_dataframe, load_corpus
from multistate_ai.pgvector_loader import dsn_from_env, load_rows

_ROOT = Path(__file__).resolve().parents[1]
_DDL = _ROOT / "sql" / "V001__doc_chunks.sql"
_SEED = Path(__file__).parent / "fixtures" / "corpus_seed.jsonl"


class _FakeMiniLM:
    """Deterministic 384-d float64 vectors; loader boundary casts to float32."""

    def encode(
        self,
        sentences: list[str],
        batch_size: int = 64,
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float64]:
        del batch_size, normalize_embeddings, convert_to_numpy
        rng = np.random.default_rng(42)
        return rng.standard_normal((len(sentences), EMBEDDING_DIM), dtype=np.float64)


def _to_psycopg_dsn(url: str) -> str:
    """Testcontainers yields a SQLAlchemy-style URL; psycopg wants postgresql://."""
    return url.replace("postgresql+psycopg2://", "postgresql://").replace(
        "postgresql+psycopg://", "postgresql://"
    )


@pytest.fixture(scope="session")
def pg_dsn() -> Iterator[str]:
    """Spin pgvector/pgvector:pg16, apply Topic 7 DDL, yield a psycopg DSN."""
    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        dsn = _to_psycopg_dsn(pg.get_connection_url())
        ddl = _DDL.read_text()
        with psycopg.connect(dsn) as conn, conn.cursor() as cur:
            cur.execute(ddl)
            conn.commit()
        yield dsn


@pytest.fixture(scope="session")
def corpus_rows() -> list[CorpusRow]:
    df = load_corpus(_SEED)
    assert len(df) == 100
    return embed_dataframe(df, model=_FakeMiniLM())


def test_load_rows_returns_100(pg_dsn: str, corpus_rows: list[CorpusRow]) -> None:
    n = load_rows(pg_dsn, corpus_rows)
    assert n == 100
    with psycopg.connect(pg_dsn) as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM doc_chunks")
        assert cur.fetchone() == (100,)


def test_load_rows_is_idempotent(pg_dsn: str, corpus_rows: list[CorpusRow]) -> None:
    first = load_rows(pg_dsn, corpus_rows)
    second = load_rows(pg_dsn, corpus_rows)
    assert first == 100
    assert second == 100
    with psycopg.connect(pg_dsn) as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM doc_chunks")
        assert cur.fetchone() == (100,)


def test_hnsw_index_present(pg_dsn: str) -> None:
    with psycopg.connect(pg_dsn) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT indexname FROM pg_indexes "
            "WHERE tablename = 'doc_chunks' AND indexname = 'doc_chunks_embedding_hnsw'"
        )
        row = cur.fetchone()
        assert row is not None
        assert row[0] == "doc_chunks_embedding_hnsw"


def test_explain_uses_hnsw_index_scan(pg_dsn: str, corpus_rows: list[CorpusRow]) -> None:
    # Ensure rows exist so the planner has something to scan.
    load_rows(pg_dsn, corpus_rows)
    query_vec = corpus_rows[0].embedding
    with psycopg.connect(pg_dsn) as conn:
        register_vector(conn)
        with conn.cursor() as cur:
            # At 100 rows the planner may prefer seq/bitmap scans; force ANN path.
            cur.execute("SET enable_seqscan = off")
            cur.execute("SET enable_bitmapscan = off")
            # Pure cosine ANN (no btree predicates) so HNSW is the only viable index.
            cur.execute(
                "EXPLAIN SELECT doc_id, chunk_idx, chunk_text, embedding <=> %s AS dist "
                "FROM doc_chunks "
                "ORDER BY embedding <=> %s LIMIT 5",
                (query_vec, query_vec),
            )
            plan = "\n".join(r[0] for r in cur.fetchall())
    assert "doc_chunks_embedding_hnsw" in plan, plan
    assert "Index Scan" in plan or "Index Only Scan" in plan, plan
    assert "Seq Scan" not in plan, plan


def test_dsn_from_env_reads_multistate_ai_pg_dsn(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MULTISTATE_AI_PG_DSN", "postgresql://user:pass@localhost:5432/db")
    assert dsn_from_env() == "postgresql://user:pass@localhost:5432/db"


def test_dsn_from_env_requires_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("MULTISTATE_AI_PG_DSN", raising=False)
    with pytest.raises(RuntimeError, match="MULTISTATE_AI_PG_DSN"):
        dsn_from_env()
