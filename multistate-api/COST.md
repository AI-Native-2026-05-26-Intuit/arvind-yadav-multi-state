# multistate-api -- Cost & LLM Observability Notes

This document covers what the multistate-cost-dev stack provisions,
how the W3D1 LLM-proxy emits cost metrics for this capstone, and
what an on-call engineer does when the alarm fires.

## Stack layout (W6 D4 extension of the W6 D3 substrate)

| Stack                      | Purpose                                              |
|----------------------------|------------------------------------------------------|
| multistate-network-dev      | (W6 D3) VPC, subnets, NAT, app SG                    |
| multistate-artifacts-dev           | (W6 D3) hardened artefact bucket                     |
| multistate-app-dev          | (W6 D3) RDS + Secrets Manager + DB SG + IRSA OIDC    |
| multistate-cost-dev         | (W6 D4) Bedrock IRSA, SNS, alarm, budget, CUR bucket |

## Per-request cost path

1.  `multistate-api` calls `POST /v1/completions` on the in-cluster
    `llm-proxy` service.
2.  The proxy's `CostMiddleware` filter forwards to the upstream
    Bedrock pick (`anthropic.claude-3-5-sonnet-20241022-v2:0`).
3.  On response, the filter computes cost in `BigDecimal` with
    `RoundingMode.HALF_UP`, converts to `cost_usd_e5` as a `long`
    via `longValueExact()`, and `HINCRBY`s the daily tenant tally
    in Redis. `HINCRBYFLOAT` is banned in this package; a CI grep
    fails the build if it appears.
4.  The filter emits one EMF document per call with dimensions
    `[[service, tenant, feature]]`. CloudWatch extracts the
    `acme/llmproxy/CostUsd` metric server-side; no
    `PutMetricData` call on the hot path.
5.  The `CostPerRequestAlarm` evaluates the 15-minute moving
    average; threshold is 0.005 USD/call; `TreatMissingData:
    breaching` so a dead pipeline pages too.

## Mandatory tags (Topic 8)

Every taggable resource in this capstone carries these four:

| Tag      | Value for this stack         | Why                                  |
|----------|------------------------------|--------------------------------------|
| service  | `multistate`           | drives per-service Budget filter     |
| env      | `dev` / `stg` / `prod`       | separates non-prod from prod signal  |
| tenant   | `shared` on infra resources  | per-tenant resources set the real id |
| feature  | `summarize-nexus`            | drives `$/feature` SLI               |

Tags must be *activated* in the AWS Billing console as
cost-allocation tags. Activation lag is the silent failure mode:
historical CUR rows from before activation have no value for the
tag column and can never be backfilled.

## What to do when the alarm fires

1.  Open the alarm in CloudWatch. State is `ALARM` (real spike)
    or `INSUFFICIENT_DATA` (cost pipeline dead -- check pod
    `llm-proxy` logs for the EMF emission).
2.  Drill the metric by tenant via Logs Insights; identify the
    offending tenant or feature.
3.  If the budget hits 100% actual, the `BudgetActionAttachDeny`
    has already attached `MultistateBedrockDenyPolicy` to
    `multistate-bedrock-invoke`. The proxy returns HTTP 503 with
    `X-Llm-Disabled-Reason: budget_cap` until a human resets.
4.  Reset path: confirm with the service owner; detach the deny
    policy from the role; tighten the budget or the upstream
    rate-limit; *then* re-enable.

## cost-author Skill audit (W6 D4 Task 4)

The `cost-author` Skill was run against
`multistate-api/summarize-nexus` with synthetic placeholders
(account 123456789012, region us-east-1). Output is in
`scratch/cost-author-output.yaml`. Audit findings:

**Accepted:** `TreatMissingData: breaching` on `CostPerRequestAlarm`.
The Skill correctly flagged that `notBreaching` would let a dead EMF
pipeline sit silently in OK forever; our template matches the accepted
recommendation.

**Rejected:** `HINCRBYFLOAT` for Redis cost tallies. The Skill
sometimes suggests floating-point increments for convenience; we reject
that categorically. Integer `cost_usd_e5` via `HINCRBY` + CI grep is
the capstone invariant — float accumulation silently corrupts finance
totals at scale.

**Rejected:** `ApprovalModel: MANUAL` on `BudgetActionAttachDeny` without
an SNS escalation path. For **dev**, `AUTOMATIC` at 100% actual is the
agreed trade-off (deny policy attaches immediately; proxy returns shaped
503). For **prod**, `MANUAL` with an explicit SNS subscriber on the
action would be defensible; dev stays AUTOMATIC.

See the gitops-repo PR body for alarm-state excerpts (OK → ALARM → OK)
captured during the synthetic spike run.
