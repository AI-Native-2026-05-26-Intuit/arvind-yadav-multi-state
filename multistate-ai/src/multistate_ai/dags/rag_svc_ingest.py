"""TaskFlow API DAG: load -> chunk -> embed -> upsert -> bump_epoch."""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow.sdk import dag, task


@dag(
    dag_id="multistate_ai_ingest",
    description="Daily ingest for multistate-ai (load -> chunk -> embed -> upsert).",
    start_date=datetime(2026, 4, 1),
    schedule="@daily",
    catchup=False,
    max_active_runs=1,
    default_args={
        "retries": 2,
        "retry_delay": timedelta(minutes=5),
        "owner": "platform-svc",
    },
    tags=["multistate_ai", "rag-svc"],
)
def multistate_ai_ingest_dag() -> None:
    @task
    def load_docs() -> list[str]:
        # Returns list of doc_ids ingested; XCom carries it forward.
        from multistate_ai.loader import load_from_source

        return load_from_source(tenants=["tenant-a", "tenant-b", "tenant-c"])

    @task
    def chunk_docs(doc_ids: list[str]) -> int:
        from multistate_ai.chunker import chunk_docs as _chunk
        from multistate_ai.loader import fetch_docs

        return len(_chunk(fetch_docs(doc_ids)))

    @task
    def embed_chunks(chunk_count: int) -> int:
        # Idempotent: skip embed call entirely when content_hash unchanged.
        del chunk_count
        from multistate_ai.embedder import embed_pending

        return embed_pending()

    @task
    def upsert_chunks(embedded_count: int) -> int:
        del embedded_count
        from multistate_ai.pgvector_loader import upsert_pending

        return upsert_pending()

    @task
    def bump_cache_epochs(upserted: int) -> None:
        del upserted
        import os

        import redis

        from multistate_ai.cache import bump_epoch

        r = redis.from_url(os.environ["MULTISTATE_AI_REDIS_URL"])
        for tenant in ("tenant-a", "tenant-b", "tenant-c"):
            bump_epoch(r, tenant)

    ids = load_docs()
    n_chunks = chunk_docs(ids)
    n_emb = embed_chunks(n_chunks)
    n_ups = upsert_chunks(n_emb)
    bump_cache_epochs(n_ups)


multistate_ai_ingest_dag()
