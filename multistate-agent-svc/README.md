# multistate-agent-svc — LangGraph multi-agent capstone (W7 D5)

Three-node graph (`retrieval_agent`, `api_agent`, `synthesis_agent`) wrapping
the W7 D4 MCP server + W7 D3 RAG sidecar. Streams SSE to the W4 D4 React
`useChat` hook.

## Quick start

```bash
cd multistate-agent-svc
cp .env.example .env   # fill keys locally; never commit .env
uv sync
uv run pytest -v tests/
uv run python -m multistate_agent_svc.scripts.eval --gate
uv run multistate-agent-svc   # uvicorn :8080
```

## Layout

See repo README / W7 D5 prompt. Key paths: `src/multistate_agent_svc/`,
`evals/`, `argo-apps/`, `cfn/`, `PROMPT_JOURNAL.md`, `RUNBOOK.md`.
