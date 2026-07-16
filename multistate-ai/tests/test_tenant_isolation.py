"""Tenant-isolation guard: every returned chunk belongs to the requester."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import numpy as np
import psycopg
import pytest
from numpy.typing import NDArray
from pgvector.psycopg import register_vector
from testcontainers.postgres import PostgresContainer

from multistate_ai.corpus import EMBEDDING_DIM, MODEL_NAME
from multistate_ai.hybrid import dense_topk_filtered

_ROOT = Path(__file__).resolve().parents[1]
_DDL_V1 = _ROOT / "sql" / "V001__doc_chunks.sql"
_DDL_V2 = _ROOT / "sql" / "V002__rag2_metadata_and_partial_indexes.sql"


def _to_psycopg_dsn(url: str) -> str:
    return url.replace("postgresql+psycopg2://", "postgresql://").replace(
        "postgresql+psycopg://", "postgresql://"
    )


def _apply_sql_file(dsn: str, path: Path) -> None:
    cleaned_lines: list[str] = []
    for line in path.read_text().splitlines():
        if line.lstrip().startswith("--"):
            continue
        cleaned_lines.append(line)
    statements = [s.strip() for s in "\n".join(cleaned_lines).split(";") if s.strip()]
    with psycopg.connect(dsn, autocommit=True) as conn:
        for stmt in statements:
            conn.execute(stmt)


def _unit(vec: NDArray[np.float32]) -> NDArray[np.float32]:
    n = float(np.linalg.norm(vec))
    return (vec / n).astype(np.float32) if n > 0 else vec


@pytest.fixture(scope="module")
def pg_dsn() -> Iterator[str]:
    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        dsn = _to_psycopg_dsn(pg.get_connection_url())
        _apply_sql_file(dsn, _DDL_V1)
        _apply_sql_file(dsn, _DDL_V2)
        qvec = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
        insert_sql = (
            "INSERT INTO doc_chunks "
            "(doc_id, chunk_idx, chunk_text, embedding, model_version, tenant_id) "
            "VALUES (%s, %s, %s, %s, %s, %s)"
        )
        rows = [
            (
                f"doc-{tenant}",
                0,
                "Nexus economic thresholds for payroll and sales.",
                qvec,
                MODEL_NAME,
                tenant,
            )
            for tenant in ("tenant-a", "tenant-b", "tenant-c")
        ]
        with psycopg.connect(dsn) as conn:
            register_vector(conn)
            with conn.cursor() as cur:
                cur.executemany(insert_sql, rows)
            conn.commit()
        yield dsn


def test_tenant_a_query_returns_no_tenant_b_chunks(pg_dsn: str) -> None:
    qvec = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
    with psycopg.connect(pg_dsn) as conn:
        register_vector(conn)
        results = dense_topk_filtered(conn, qvec, "tenant-a", k=20, model_version=MODEL_NAME)
    assert results, "expected at least one tenant-a hit"
    doc_ids = [r[0] for r in results]
    with psycopg.connect(pg_dsn) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT DISTINCT tenant_id FROM doc_chunks WHERE doc_id = ANY(%s)",
            (doc_ids,),
        )
        tenants = {row[0] for row in cur.fetchall()}
    assert tenants == {"tenant-a"}, f"tenant leak detected: {tenants}"
