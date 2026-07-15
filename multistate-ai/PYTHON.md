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
calls; a dummy URL is fine during day-to-day pytest.

Local Rancher Desktop note: set `TESTCONTAINERS_RYUK_DISABLED=true` (already
defaulted in `tests/conftest.py`) so Ryuk does not try to mount
`~/.rd/docker.sock`. On macOS with corp TLS, export `SSL_CERT_FILE=/etc/ssl/cert.pem`
(and `REQUESTS_CA_BUNDLE`) before LangSmith / Anthropic SaaS calls.

RAGAS: CI runs a real Anthropic-backed ``ragas.evaluate`` (``ChatAnthropic`` via
``LangchainLLMWrapper`` + local MiniLM embeddings) so threshold floors reflect
live metric scores — not a hardcoded offline proxy. Override the evaluator with
``MULTISTATE_AI_RAGAS_MODEL`` (default ``claude-haiku-4-5-20251001``).


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
