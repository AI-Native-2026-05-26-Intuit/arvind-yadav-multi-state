"""Great Expectations checkpoint via Testcontainers Postgres + pgvector."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import great_expectations as gx
import numpy as np
import pandas as pd
import psycopg
import pytest
from ddl_util import apply_sql_file
from great_expectations.core import ExpectationSuite
from great_expectations.core.validation_definition import ValidationDefinition
from great_expectations.expectations.core.expect_column_value_lengths_to_be_between import (
    ExpectColumnValueLengthsToBeBetween,
)
from great_expectations.expectations.core.expect_column_values_to_not_be_null import (
    ExpectColumnValuesToNotBeNull,
)
from great_expectations.expectations.core.expect_table_row_count_to_be_between import (
    ExpectTableRowCountToBeBetween,
)
from numpy.typing import NDArray
from testcontainers.postgres import PostgresContainer

from multistate_ai.corpus import EMBEDDING_DIM, embed_dataframe, load_corpus
from multistate_ai.pgvector_loader import load_rows

SUITE_NAME = "doc_chunks_v1"
_ROOT = Path(__file__).resolve().parents[1]
_DDL_V1 = _ROOT / "sql" / "V001__doc_chunks.sql"
_DDL_V2 = _ROOT / "sql" / "V002__rag2_metadata_and_partial_indexes.sql"
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
        rng = np.random.default_rng(99)
        return rng.standard_normal((len(sentences), EMBEDDING_DIM), dtype=np.float64)


def _to_psycopg_dsn(url: str) -> str:
    return url.replace("postgresql+psycopg2://", "postgresql://").replace(
        "postgresql+psycopg://", "postgresql://"
    )


@pytest.fixture(scope="module")
def pg_dsn() -> Iterator[str]:
    """Spin Postgres + pgvector, apply DDL, seed >=100 chunks."""
    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        dsn = _to_psycopg_dsn(pg.get_connection_url())
        apply_sql_file(dsn, _DDL_V1)
        apply_sql_file(dsn, _DDL_V2)
        df = load_corpus(_SEED)
        rows = embed_dataframe(df, model=_FakeMiniLM())
        n = load_rows(dsn, rows)
        assert n >= 100, f"seeded only {n} rows; need >=100"
        yield dsn


def _read_doc_chunks(dsn: str) -> pd.DataFrame:
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT doc_id, chunk_idx, chunk_text, embedding::text AS embedding, "
            "model_version, tenant_id FROM doc_chunks"
        )
        colnames = [desc.name for desc in cur.description] if cur.description else []
        data = cur.fetchall()
    return pd.DataFrame(data, columns=colnames)


def test_doc_chunks_suite_passes(pg_dsn: str) -> None:
    frame = _read_doc_chunks(pg_dsn)
    assert len(frame) >= 100

    context = gx.get_context(mode="ephemeral")
    data_source = context.data_sources.add_pandas(name="doc_chunks_pandas")
    data_asset = data_source.add_dataframe_asset(name="doc_chunks")
    batch_definition = data_asset.add_batch_definition_whole_dataframe("whole")

    suite = context.suites.add(ExpectationSuite(name=SUITE_NAME))
    expectations = [
        ExpectColumnValuesToNotBeNull(column="doc_id"),
        ExpectColumnValuesToNotBeNull(column="embedding"),
        ExpectColumnValuesToNotBeNull(column="model_version"),
        ExpectTableRowCountToBeBetween(min_value=100, max_value=10_000_000),
        ExpectColumnValueLengthsToBeBetween(column="chunk_text", min_value=1, max_value=8000),
    ]
    assert len(expectations) >= 5
    for expectation in expectations:
        suite.add_expectation(expectation)

    validation_definition = context.validation_definitions.add(
        ValidationDefinition(
            name=f"{SUITE_NAME}_vd",
            data=batch_definition,
            suite=suite,
        )
    )
    result = validation_definition.run(batch_parameters={"dataframe": frame})
    assert result.success is True, result
