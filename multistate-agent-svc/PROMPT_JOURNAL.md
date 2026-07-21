# multistate-agent-svc/PROMPT_JOURNAL.md

## W7 D5 transcripts

### 1. Three-node StateGraph with supervisor + parallel fan-out

**Prompt to Claude:**

> Extend my W7 D4 single-node LangGraph into a W7 D5 three-node graph:
> AgentState TypedDict (question, tenant_id, docs, tool_results, answer)
> with reducers on the parallel slots, a supervisor router returning
> list[Send], retrieval + api + synthesis nodes, PostgresSaver
> checkpointer, recursion_limit=25. Use claude-sonnet-4-5 and tenant
> ids tenant-a/b/c only.

**Raw Claude response (excerpt):**

> Scaffolded StateGraph with three nodes and a keyword supervisor, but
> left `docs` / `tool_results` as bare list/dict fields and used
> MemorySaver for local smoke tests. Sync `PostgresSaver` + `graph.invoke`
> for async MCP/Anthropic nodes.

**Use as is / Modified / Rejected:** Modified — added
`Annotated[list[dict], operator.add]` on docs and a custom
last-write-wins merger on tool_results so fan-out cannot overwrite.
Switched to `AsyncPostgresSaver` + `ainvoke` because all worker nodes
are async. Pinned `recursion_limit=25` on every call site. No
MemorySaver in `src/`.

### 2. Instructor-typed FinalAnswer + refusal path

**Prompt to Claude:**

> Write a synthesis node that uses Instructor + Claude to return a typed
> FinalAnswer (text, citations, confidence) with ConfigDict(extra="forbid"),
> max_retries=2, and a refusal path when both docs and tool_results are empty.

**Raw Claude response (excerpt):**

> Returned free-text from `messages.create` and suggested stuffing
> citations as markdown footnotes without a Pydantic response model.

**Use as is / Modified / Rejected:** Modified — switched to Instructor's
`response_model=FinalAnswer` with `max_retries=2`, and added the
empty-context refusal language (confidence < 0.4, zero fabricated
citations). Offline CI path returns a typed refusal when
`ANTHROPIC_API_KEY` is unset.

### 3. Trajectory eval + RAGAS faithfulness gate

**Prompt to Claude:**

> Author a 20-row trajectory eval with golden node sequences, a
> trajectory_match helper, and a CI gate that fails on trajectory < 0.70,
> faithfulness < 0.85, or cost-per-run regression > 15%.

**Raw Claude response (excerpt):**

> Produced the Scenario dataclass + trajectory_match subset check and
> wired ragas.evaluate on faithfulness only; cost regression omitted.

**Use as is / Modified / Rejected:** Used as is for the scenario shape;
modified the gate to also fail on cost-per-run regression > 15% and
added a local faithfulness stand-in for CI when live RAGAS is not
enabled (`MULTISTATE_AGENT_EVAL_RAGAS=1` opts into real RAGAS).
