"""Assert that a retrieve_chunks run appears in the LangSmith project.

Runs one annotated retrieval against Testcontainers Postgres, waits for the
async LangSmith flush, then queries the SaaS project for at least one run
named ``multistate_ai.retrieve_chunks``. Exits non-zero when zero runs are
found — grepping the source tree for @traceable is not enough.
"""

from __future__ import annotations

import os
import sys
import time
from functools import lru_cache
from pathlib import Path

import numpy as np
import psycopg
from numpy.typing import NDArray
from testcontainers.postgres import PostgresContainer

from multistate_ai.corpus import EMBEDDING_DIM, MODEL_NAME, CorpusRow, embed_dataframe, load_corpus
from multistate_ai.pgvector_loader import load_rows
from multistate_ai.rag import retrieve_chunks

_ROOT = Path(__file__).resolve().parents[3]  # multistate-ai/
_DDL = _ROOT / "sql" / "V001__doc_chunks.sql"
_SEED = _ROOT / "tests" / "fixtures" / "corpus_seed.jsonl"
_RUN_NAME = "multistate_ai.retrieve_chunks"
_DEFAULT_PROJECT = "multistate-ai-dev-ci"


class _FakeMiniLM:
    def encode(
        self,
        sentences: list[str],
        batch_size: int = 64,
        normalize_embeddings: bool = True,
        convert_to_numpy: bool = True,
    ) -> NDArray[np.float64]:
        del batch_size, normalize_embeddings, convert_to_numpy
        rng = np.random.default_rng(7)
        return rng.standard_normal((len(sentences), EMBEDDING_DIM), dtype=np.float64)


def _to_psycopg_dsn(url: str) -> str:
    return url.replace("postgresql+psycopg2://", "postgresql://").replace(
        "postgresql+psycopg://", "postgresql://"
    )


def _seed(dsn: str) -> None:
    ddl = _DDL.read_text()
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        cur.execute(ddl)
        conn.commit()
    df = load_corpus(_SEED)
    rows = embed_dataframe(df, model=_FakeMiniLM())
    tenant_a = [r for r in rows if r.tenant_id == "tenant-a"]
    if not tenant_a:
        tenant_a = [
            CorpusRow(
                doc_id="tenant-001",
                chunk_idx=0,
                chunk_text="Wayfair economic nexus floor for California remote sellers.",
                embedding=np.zeros(EMBEDDING_DIM, dtype=np.float32),
                model_version=MODEL_NAME,
                tenant_id="tenant-a",
            )
        ]
    load_rows(dsn, tenant_a)


def _install_fake_embedder() -> None:
    """Avoid HuggingFace downloads in CI; still exercise @traceable + ANN SQL."""
    from multistate_ai import rag as rag_mod

    rag_mod._embedding_model.cache_clear()

    @lru_cache(maxsize=1)
    def _fake() -> _FakeMiniLM:
        return _FakeMiniLM()

    rag_mod._embedding_model = _fake  # type: ignore[assignment]


def main() -> int:
    os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")
    if not os.environ.get("LANGSMITH_API_KEY") and os.environ.get(
        "MULTISTATE_AI_LANGSMITH_API_KEY"
    ):
        os.environ["LANGSMITH_API_KEY"] = os.environ["MULTISTATE_AI_LANGSMITH_API_KEY"]
    project = os.environ.get("LANGSMITH_PROJECT") or os.environ.get(
        "MULTISTATE_AI_LANGSMITH_PROJECT", _DEFAULT_PROJECT
    )
    os.environ["LANGSMITH_PROJECT"] = project
    os.environ.setdefault("LANGSMITH_TRACING", "true")

    if not os.environ.get("LANGSMITH_API_KEY"):
        print("LANGSMITH_API_KEY is required", file=sys.stderr)
        return 2

    _install_fake_embedder()

    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        dsn = _to_psycopg_dsn(pg.get_connection_url())
        _seed(dsn)
        hits = retrieve_chunks(
            dsn,
            question="What dollar floor creates California economic nexus?",
            k=3,
            tenant_id="tenant-a",
        )
        if not hits:
            print("retrieve_chunks returned zero hits; seed may be empty", file=sys.stderr)
            return 3

    time.sleep(5)

    from langsmith import Client

    client = Client()
    runs = list(client.list_runs(project_name=project, name=_RUN_NAME, limit=5))
    if not runs:
        print(
            f"no LangSmith runs named {_RUN_NAME!r} in project {project!r}",
            file=sys.stderr,
        )
        return 1
    print(f"found {len(runs)} run(s) named {_RUN_NAME} in {project}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
