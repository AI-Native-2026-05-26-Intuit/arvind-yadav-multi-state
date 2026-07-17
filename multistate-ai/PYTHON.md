# Python sidecar for multistate-api

## What lives here

The Python sidecar that owns nexus review for multistate-api. The Java service
(multistate-api) keeps the transactional Postgres workload and the
latency-sensitive HTTP surface; this sidecar calls the W3 D1 LLM proxy and
returns a typed `NexusReviewResult`.

## How to run locally

```bash
cd multistate-ai
uv sync
uv run pytest -v
uv run mypy src/ tests/
uv run ruff check
uv run python src/multistate_ai/cli.py tests/fixtures/sample_request.json
```

## Boundary contract

- Pydantic v2 models in `src/multistate_ai/models.py` use `populate_by_name=True`
  and `alias=` so the JSON wire form matches the Java side's camelCase output
  (`tenantId`, `createdAt`). Round-trip test: `tests/test_models.py`.
- Money is `decimal.Decimal`, never `float`. `Decimal` aligns with Java's
  `BigDecimal` on the wire and survives JSON serialisation without precision loss.

## AI authoring discipline

Claude scaffolded the first cut of the Pydantic models and the httpx client.
Deviations from Claude's output that we corrected:

1. **Rejected: `str` for the API key.** Claude typed `proxy_api_key: str`.
   We changed it to `SecretStr` so the key never appears in logs, repr, or
   serialised settings output. `get_secret_value()` is only called at the
   Bearer header construction site in `client.py`.

2. **Rejected: `List[X]` / `Optional[X]` from `typing`.** Claude's scaffold
   used legacy generics. We use PEP 604 unions (`X | None`) and built-in
   `list[...]` / `tuple[...]` throughout; `grep` for `List[` / `Optional[`
   in `src/` returns zero matches.

3. **Accepted: tenacity `@retry` on the httpx call path.** We kept the
   decorator shape but replaced blind `HTTPStatusError` retry with
   `_is_retryable()` so 4xx client errors fail fast (one attempt) while 5xx
   still retries up to three times.

4. **Rejected: `dict[str, Any]` request payload.** Claude annotated
   `model_dump()` as `dict[str, Any]`, which violates `disallow_any_explicit`.
   We send `content=request.model_dump_json(by_alias=True)` instead, avoiding
   `Any` entirely in `src/`.

## What W7 D2 adds

This sidecar gained the data-and-AI-observability stack today:

* `src/multistate_ai/corpus.py` — Pandas corpus loader,
  de-dup on `(doc_id, chunk_idx)`, `NDArray[np.float32]` discipline.
* `src/multistate_ai/pgvector_loader.py` — psycopg v3 +
  `register_vector`, `cur.executemany`, `ON CONFLICT DO UPDATE`
  for idempotent retries.
* `src/multistate_ai/rag.py` — `@traceable(run_type="retriever")`
  on the ANN search call; LangSmith project `multistate-ai-dev`.
* `sql/V001__doc_chunks.sql` — extended pgvector DDL with the
  `model_version` column and the HNSW `vector_cosine_ops` index.
* `tests/test_ragas_thresholds.py` — 50-row golden set;
  RAGAS faithfulness/answer_relevancy/context_precision/context_recall
  threshold assertions.
* `tests/test_great_expectations_suite.py` — Testcontainers
  Postgres + pgvector spin-up; GX checkpoint `doc_chunks_v1`
  asserts column non-null + row count + length bounds.

## How to run today's additions

```bash
uv sync                                 # picks up the eight new deps
uv run pytest -v tests/test_corpus.py tests/test_pgvector_loader.py
uv run pytest -v tests/test_ragas_thresholds.py          # needs ANTHROPIC_API_KEY
uv run pytest -v tests/test_great_expectations_suite.py
uv run python -m multistate_ai.scripts.assert_langsmith_run_visible
```

**Postgres:** you do **not** need a local Postgres for W7 D2 tests.  
`tests/test_pgvector_loader.py`, `test_great_expectations_suite.py`, and
`assert_langsmith_run_visible` spin `pgvector/pgvector:pg16` via Testcontainers.
`MULTISTATE_AI_PG_DSN` feeds `dsn_from_env()` / `retrieve_chunks_from_env()` for
production-like runs; pytest and the LangSmith assert script fall back to
Testcontainers when that env var is unset.

Local Rancher Desktop note: set `TESTCONTAINERS_RYUK_DISABLED=true` (already
defaulted in `tests/conftest.py`) so Ryuk does not try to mount
`~/.rd/docker.sock`. On macOS with corp TLS, export `SSL_CERT_FILE=/etc/ssl/cert.pem`
(and `REQUESTS_CA_BUNDLE`) before LangSmith / Anthropic SaaS calls.

RAGAS: CI runs a real Anthropic-backed ``ragas.evaluate`` (``ChatAnthropic`` via
``LangchainLLMWrapper`` + local MiniLM embeddings) on a stratified ~10-row
sample (all three failure modes + happy-path rows). The full 50-row structural
gate runs without LLM calls. Quota exhaustion ``pytest.skip``s; wiring bugs
``pytest.fail``. Set ``MULTISTATE_AI_RAGAS_FULL_EVAL=true`` locally for 50-row
live eval. Override model via ``MULTISTATE_AI_RAGAS_MODEL``.


## AI authoring discipline (W7 D2 additions)

Claude scaffolded the first cut of `corpus.py`, `pgvector_loader.py`, and
the GX suite. Concrete deviations from Claude's output that we corrected:

1. **Rejected: `np.float64` embeddings.** Claude left MiniLM / fake-encode
   vectors as float64. Downstream pgvector inserts and the assignment
   boundary require `NDArray[np.float32]` via `.astype(np.float32)` in
   `embed_dataframe`.

2. **Rejected: insert without `register_vector(conn)`.** Claude's loader
   called `executemany` directly; without `pgvector.psycopg.register_vector`
   the embedding column silently lands as malformed bytes. We register
   before every insert / ANN query.

3. **Rejected: inline LangSmith personal API key in source.** Claude wired
   `Client(api_key="…")` with a hardcoded personal token into the retriever
   scaffold. We raise when `LANGSMITH_API_KEY` is missing and let the
   LangSmith SDK read credentials exclusively from `os.environ` / CI
   `secrets.*`.

## What this sidecar does NOT do (yet)

- MCP server publishing — W7 D4.
- LangGraph orchestration — W7 D5.

## What W7 D3 adds

This sidecar gained the RAG 2.0 production retrieval stack today:

* ``sql/V002__rag2_metadata_and_partial_indexes.sql`` — JSONB
  metadata column, GIN ``jsonb_path_ops`` index, per-tenant partial
  HNSW indexes for ``tenant-a``/``tenant-b``/``tenant-c``, FTS
  ``tsvector`` column, FTS GIN index.
* ``src/multistate_ai/chunker.py`` —
  ``RecursiveCharacterTextSplitter`` at ``chunk_size=900`` /
  ``overlap=150``; synthetic ``chunk_id`` discipline.
* ``src/multistate_ai/hybrid.py`` — ``RetrievedChunk`` keyed by
  ``chunk_id`` (parent ``doc_id`` kept for citations),
  ``dense_topk_filtered``, ``sparse_topk_fts``, ``rrf_fuse``
  (k=60), ``coverage`` diagnostic. Every retriever decorated
  with ``@traceable``.
* ``src/multistate_ai/rerank.py`` — ``mmr_pick``
  (lambda=0.7) and ``bge_rerank`` against
  ``BAAI/bge-reranker-base`` with a strict 300 ms
  timeout-and-fallback.
* ``src/multistate_ai/cache.py`` — Redis semantic cache keyed
  by (tenant_id, epoch, quantised-embedding); ``bump_epoch`` per
  tenant on Airflow ingest completion.
* ``src/multistate_ai/dags/rag_svc_ingest.py`` — TaskFlow API
  DAG (``load_docs -> chunk_docs -> embed_chunks -> upsert_chunks ->
  bump_cache_epochs``).
* ``src/multistate_ai/rag.py`` — the ``retrieve_and_generate``
  entry point Thursday's MCP server will publish (W7 D2
  ``retrieve_chunks`` retained for LangSmith asserts).
* ``tests/test_tenant_isolation.py`` — Testcontainers proof
  that ``tenant-a`` queries never return ``tenant-b`` chunks.
* ``tests/test_ragas_gate.py`` — faithfulness >=
  0.85 CI gate.
* ``docs/ragas/w7d3.md`` — before-vs-after RAGAS report.

## How to run today's additions

```bash
uv sync                                # picks up the new deps
uv run pytest -v tests/test_tenant_isolation.py
uv run pytest -v tests/test_semantic_cache.py
uv run pytest -v tests/test_ragas_gate.py
uv run python -c "from multistate_ai.dags.rag_svc_ingest import multistate_ai_ingest_dag"
```

## AI authoring discipline (W7 D3 additions)

Claude scaffolded the first cut of ``hybrid.py``, ``rerank.py``, and
the before-vs-after report. Two deviations from Claude's output we corrected:

1. **Rejected: ``0.5 * cosine + 0.5 * bm25`` score fusion.** Claude's
   hybrid scaffold averaged dense cosine and sparse ``ts_rank_cd`` after
   min-max normalisation. Those scales are incomparable; we use Reciprocal
   Rank Fusion (``k_const=60``) on ranks only — see Topic 7 watch-out.

2. **Rejected: rerank without a hard timeout-and-fallback.** Claude's
   ``bge_rerank`` called ``CrossEncoder.predict`` with no latency cap. We
   keep a soft 300 ms budget, fall back to retrieval order on overrun, set
   ``rerank_timed_out`` on the LangSmith span, and export
   ``rerank_timeout_count`` for SRE alerts so the p99 tail cannot blow the SLO.

3. **Rejected: cache key without ``tenant_id`` / citation defence.** Claude
   keyed Redis on the quantised embedding alone. We include
   ``tenant_id`` + per-tenant epoch in the key and treat any citation whose
   ``tenant_id`` mismatches the requester as a cache miss.
