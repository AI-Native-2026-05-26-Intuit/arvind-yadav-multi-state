"""Hybrid retrieval tests: dense metadata filter, FTS phrase hit, RRF, coverage."""

from __future__ import annotations

import json
import math
from collections.abc import Iterator
from pathlib import Path

import numpy as np
import psycopg
import pytest
from numpy.typing import NDArray
from pgvector.psycopg import register_vector
from testcontainers.postgres import PostgresContainer

from multistate_ai.corpus import EMBEDDING_DIM, MODEL_NAME
from multistate_ai.hybrid import (
    RetrievedChunk,
    coverage,
    dense_topk_filtered,
    rrf_fuse,
    sparse_topk_fts,
)

_ROOT = Path(__file__).resolve().parents[1]
_DDL_V1 = _ROOT / "sql" / "V001__doc_chunks.sql"
_DDL_V2 = _ROOT / "sql" / "V002__rag2_metadata_and_partial_indexes.sql"

_PRODUCT_CODE = "SKU-NX-99421"
_MODEL = MODEL_NAME


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
        yield dsn


@pytest.fixture(scope="module")
def seeded_pg(pg_dsn: str) -> str:
    """Seed tenant-a rows: metadata-tagged docs, generic nexus prose, one product code."""
    query_like = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
    orthogonal = _unit(np.concatenate([np.ones(EMBEDDING_DIM // 2), -np.ones(EMBEDDING_DIM // 2)]))
    insert_sql = (
        "INSERT INTO doc_chunks "
        "(doc_id, chunk_idx, chunk_text, embedding, model_version, tenant_id, "
        " chunk_metadata, content_hash) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb, %s)"
    )
    rows: list[tuple[object, ...]] = [
        (
            "doc-ca-sales",
            0,
            "California economic nexus sales threshold overview.",
            query_like,
            _MODEL,
            "tenant-a",
            json.dumps({"state": "CA", "topic": "sales"}),
            "hash-ca",
        ),
        (
            "doc-ny-payroll",
            0,
            "New York payroll withholding nexus registration rules.",
            query_like,
            _MODEL,
            "tenant-a",
            json.dumps({"state": "NY", "topic": "payroll"}),
            "hash-ny",
        ),
        (
            "doc-generic-0",
            0,
            "Economic nexus thresholds apply when sales exceed the statutory floor.",
            query_like,
            _MODEL,
            "tenant-a",
            json.dumps({}),
            "hash-g0",
        ),
        (
            "doc-generic-1",
            0,
            "Remote seller nexus depends on gross receipts in the destination state.",
            query_like,
            _MODEL,
            "tenant-a",
            json.dumps({}),
            "hash-g1",
        ),
        (
            "doc-product-sku",
            0,
            f"Inventory SKU reference {_PRODUCT_CODE} for multistate fulfillment.",
            orthogonal,
            _MODEL,
            "tenant-a",
            json.dumps({"sku": _PRODUCT_CODE}),
            "hash-sku",
        ),
        # Two chunks under the same doc_id — proves fusion keys on chunk_id.
        (
            "doc-multi",
            0,
            "First chunk of a multi-chunk nexus guide about sales thresholds.",
            query_like,
            _MODEL,
            "tenant-a",
            json.dumps({"part": "1"}),
            "hash-m0",
        ),
        (
            "doc-multi",
            1,
            "Second chunk of a multi-chunk nexus guide about payroll days.",
            query_like,
            _MODEL,
            "tenant-a",
            json.dumps({"part": "2"}),
            "hash-m1",
        ),
    ]
    with psycopg.connect(pg_dsn) as conn:
        register_vector(conn)
        with conn.cursor() as cur:
            cur.executemany(insert_sql, rows)
        conn.commit()
    return pg_dsn


def test_dense_topk_filtered_applies_metadata_containment(seeded_pg: str) -> None:
    qvec = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
    with psycopg.connect(seeded_pg) as conn:
        register_vector(conn)
        hits = dense_topk_filtered(
            conn,
            qvec,
            "tenant-a",
            metadata_filter={"state": "CA"},
            k=10,
            model_version=_MODEL,
        )
    assert hits
    assert all(h.doc_id == "doc-ca-sales" for h in hits)
    assert all(h.chunk_id for h in hits)
    with psycopg.connect(seeded_pg) as conn:
        register_vector(conn)
        unfiltered = dense_topk_filtered(conn, qvec, "tenant-a", k=10, model_version=_MODEL)
    assert len(unfiltered) > len(hits)


def test_sparse_topk_fts_ranks_exact_product_code(seeded_pg: str) -> None:
    qvec = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
    with psycopg.connect(seeded_pg) as conn:
        register_vector(conn)
        dense = dense_topk_filtered(conn, qvec, "tenant-a", k=50, model_version=_MODEL)
        sparse = sparse_topk_fts(conn, f'"{_PRODUCT_CODE}"', "tenant-a", k=50)

    dense_docs = [h.doc_id for h in dense]
    sparse_docs = [h.doc_id for h in sparse]
    assert "doc-product-sku" in sparse_docs
    assert sparse_docs[0] == "doc-product-sku"
    assert dense_docs.index("doc-product-sku") > 0


def test_rrf_fuse_keeps_distinct_chunks_from_same_doc() -> None:
    """Multi-chunk same doc_id must not collapse under one RRF key."""
    a = RetrievedChunk("1", "doc-multi", "chunk A", 0.1)
    b = RetrievedChunk("2", "doc-multi", "chunk B", 0.2)
    fused = rrf_fuse([a], [b], top_k=60)
    assert {h.chunk_id for h in fused} == {"1", "2"}
    assert all(h.doc_id == "doc-multi" for h in fused)


def test_rrf_fuse_union_of_disjoint_top50() -> None:
    dense = [RetrievedChunk(f"d-{i}", f"doc-d-{i}", f"dense text {i}", float(i)) for i in range(50)]
    sparse = [
        RetrievedChunk(f"s-{i}", f"doc-s-{i}", f"sparse text {i}", float(i)) for i in range(50)
    ]
    dense_ids = {h.chunk_id for h in dense}
    sparse_ids = {h.chunk_id for h in sparse}
    fused = rrf_fuse(dense, sparse, top_k=60)
    fused_ids = {h.chunk_id for h in fused}
    assert len(fused) == 60
    assert fused_ids <= (dense_ids | sparse_ids)
    assert fused_ids & dense_ids
    assert fused_ids & sparse_ids
    assert "d-0" in fused_ids and "s-0" in fused_ids


def test_coverage_jaccard_finite_unit_interval(seeded_pg: str) -> None:
    qvec = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
    with psycopg.connect(seeded_pg) as conn:
        register_vector(conn)
        dense = dense_topk_filtered(conn, qvec, "tenant-a", k=50, model_version=_MODEL)
        sparse = sparse_topk_fts(conn, "economic nexus thresholds", "tenant-a", k=50)
    diag = coverage(dense, sparse)
    j = diag["jaccard"]
    assert math.isfinite(j)
    assert 0.0 <= j <= 1.0
    assert diag["dense_only"] + diag["sparse_only"] + diag["both"] == float(
        len({h.chunk_id for h in dense} | {h.chunk_id for h in sparse})
    )


def test_dense_returns_distinct_chunk_ids_for_multi_chunk_doc(seeded_pg: str) -> None:
    qvec = _unit(np.ones(EMBEDDING_DIM, dtype=np.float32))
    with psycopg.connect(seeded_pg) as conn:
        register_vector(conn)
        hits = dense_topk_filtered(conn, qvec, "tenant-a", k=50, model_version=_MODEL)
    multi = [h for h in hits if h.doc_id == "doc-multi"]
    assert len(multi) == 2
    assert multi[0].chunk_id != multi[1].chunk_id
