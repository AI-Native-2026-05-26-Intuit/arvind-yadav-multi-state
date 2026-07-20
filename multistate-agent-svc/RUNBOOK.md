# multistate-agent-svc/RUNBOOK.md

# agent-svc on-call runbook (W7 D5)

## Top-five signals

| # | Signal | Why it matters | First action |
|---|---|---|---|
| 1 | synthesis cost p99 breach | Dollar budget burns on the final LLM call | Check LangSmith `synthesis_agent` spans; tighten prompt / max_tokens; confirm BudgetGuard ceiling |
| 2 | retrieval p99 over deadline (3.0s) | `@deadline` sentinel empties `docs` → refusal answers | Inspect Redis cache hit rate + BGE rerank timeouts; scale pgvector / lower top-k |
| 3 | RAGAS faithfulness 7-day median drop > 0.10 | Grounding regression | Diff `evals/last_run.json`, re-run corpus ingest DAG, check citation reshape |
| 4 | BudgetAction fired (100% of $4000) | IAM DENY on llm-proxy for workload role | Detach Deny policy only after root-cause; raise budget only with ES approval |
| 5 | Argo CD OutOfSync for `multistate-agent-svc` | Drift or failed image bump | `argocd app get multistate-agent-svc`; inspect config-repo overlay tag |

## CloudFormation / Budgets IAM note

Stack `multistate-agent-anthropic-monthly` is **CREATE_COMPLETE** with budget
`multistate-agent-anthropic-monthly` ($4000 COST / MONTHLY). IAM role/policy/topic
names use a `-w7d5v4` suffix because orphan roles from earlier failed deletes
block recreate of the rubric name `multistate-agent-svc-role` (trainee lacks
`iam:DeleteRolePolicy`).

`budgets:CreateBudgetAction` works after Resource includes
`.../budget/multistate-agent-anthropic-monthly/action/*`. BudgetsAction was
created successfully (ActionId `d7dca034-b3c2-4b00-9703-73434474ad61`).

Managing the same action via CloudFormation (`CreateBudgetAction=true`) still
hits `AWS::EarlyValidation::PropertyValidation`. Confirm the SNS email for
`multistate-agent-anthropic-budget-alerts-w7d5v4` (may still show
`PendingConfirmation`), then:

```bash
aws cloudformation deploy \
  --stack-name multistate-agent-anthropic-monthly \
  --template-file multistate-agent-svc/cfn/agent-svc-budget.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --region us-east-1 \
  --parameter-overrides CreateBudgetAction=true
```

## Defence-in-depth

- **Fast budget:** `recursion_limit=25` → `GraphRecursionError`
- **Slow budget:** `BudgetGuard` ceiling `25000` (1e-5 USD) → HTTP 503 + `Retry-After`
- Checkpointer: `AsyncPostgresSaver` keyed by `thread_id` (HITL resume)

## 30 / 60 / 90

- **Day 30 — production hardening:** per-tenant rate limits in `supervisor`, SLO burn alerts on synthesis cost, canary image tags.
- **Day 60 — scope expansion:** new MCP tools (catalogue search, shipment tracking) discovered via `session.list_tools()` — no hardcoded catalogue.
- **Day 90 — multi-region + tenant-isolated pools:** regional Postgres checkpoint stores, tenant-pinned agent pools, cross-region failover drill.

## Rollback rehearsal (recorded)

Rehearsed locally against the config-repo image-tag bump pattern (W6 D2):

1. Note current tag in `overlays/agent-svc` (or agent-svc Application source).
2. Revert the image tag bump commit / PR on `arvind-yadav-multistate-config`.
3. Watch Argo CD auto-sync (or `kubectl -n multistate-svc get pods -o wide` once applied).
4. Verify pod labels show the prior image SHA.

| Field | Value |
|---|---|
| Reverted SHA (forward) | _(fill after first prod bump)_ |
| Rolled back to SHA | _(prior image digest)_ |
| Argo auto-sync wall-clock | _(seconds)_ |
| Verification | `kubectl -n multistate-svc get pods -l app=multistate-agent-svc -o jsonpath='{.items[*].spec.containers[*].image}'` |

Until the first live bump lands, the rehearsal procedure above is the
authoritative rollback path; update this table when the first revert is
executed in-cluster.
