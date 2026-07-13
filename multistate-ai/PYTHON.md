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

## What this sidecar does NOT do (yet)

- NumPy + Pandas analytics — W7 D2.
- pgvector retrieval / RAG — W7 D3.
- MCP server publishing — W7 D4.
- LangGraph orchestration — W7 D5.
