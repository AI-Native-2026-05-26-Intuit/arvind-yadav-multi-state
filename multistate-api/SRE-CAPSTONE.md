# multistate-api -- SRE Capstone (Week 6 Day 5)

This document is the production-readiness checklist for
multistate-api. It threads the W6 D1-D4 substrate
(CI/CD, GitOps, CloudFormation, cost SLI) with today's
scaling, tracing, and load-test CI gate. The W7 capstone
uses this document as the template every later service's
SRE doc begins from.

## Friday landing artefacts

| Artefact | Purpose | Composes with |
|----------|---------|---------------|
| `k8s/multistate-api/multistate-worker-scaledobject.yaml` | KEDA scales worker on multistate-nexus-jobs-dev depth | W3D3 queue lag pattern |
| `cfn/multistate-observability-dev.yaml` | X-Ray sampling rule (reservoir 10, rate 0.05) | W6D3 CFN ChangeSet flow |
| `k8s/multistate-api/adot-collector.yaml` | ADOT collector dual-exports OTLP to Tempo + X-Ray | W5D5 OTel SDK |
| `k8s/multistate-api/hpa.yaml` | SLO-derived HPA on multistate_inflight_requests | W5D3 HPA, W5D5 SLO |
| `k8s/karpenter/multistate-mixed.yaml` | Spot + On-Demand mix with PDB | W6D4 cost-SLI gate |
| `loadtests/multistate-api-p99.js` | k6 thresholds map exactly to W5D5 SLO numbers | W6D5 load CI gate |
| `.github/workflows/load.yml` | Required dispatch gate; build fails when SLO breaches | W6D1 OIDC + environment approval |

## SLOs (W5D5 contract restated)

| SLO | Target | Threshold expression |
|-----|--------|----------------------|
| p99 latency | < 900 ms | `http_req_duration: ['p(99)<900']` |
| Error rate | < 0.005 | `http_req_failed: ['rate<0.005']` |
| Cost per request | < 0.006 USD | `cost_per_request_usd: ['p(95)<0.006']` (W6D4) |

The k6 script reads `X-Cost-Usd` from the W6D4 proxy and
gates the cost SLI alongside latency. Drifting either
number requires a PR touching both this doc and the k6
script.

## Cost-aware scaling rule

The HPA target 7 inflight requests per
replica comes from a single-replica saturation test: we
ramped one pod until p99 hit 900 ms; that is
the SLO-derived target. Little's Law in one line:
`replicas = offered_rps / (per_replica_rps * 0.7)`.

When cost SLI breaches while latency is well under
budget, the cost-aware rule scales IN by a step. The
signal is the W6D4 `acme/llmproxy/CostUsd`
metric breaking the 0.006 USD threshold while
p99 sits well under 900 ms.

## Trace investigation: Tempo or X-Ray?

| Question | Tool | Why |
|----------|------|-----|
| Why did Bedrock take 1.2s on this trace? | X-Ray | Bedrock emits an X-Ray segment. |
| Filter traces tagged `tenant=tenant-synth` in last 24h | Tempo | TraceQL attribute filters at scale. |
| Service map multistate-api → llm-proxy → Bedrock | X-Ray | Time-aggregated topology view. |
| Spans on a specific `k8s.pod.name` | Tempo | k8s attributes Tempo natively indexes. |

The ADOT collector preserves the 16-byte OTel trace ID
through `awsxray` translation; a trace ID pastes from
Tempo into X-Ray and lands on the same trace.

## Integration spike (Friday lab)

Run: `COUNT=4000 ./scripts/spike-runner.sh`

Expected timeline (paste actuals into PR after run):

- T+00:00 — Spike script started.
- T+00:30 — KEDA observed depth > 10/pod; scaled worker from 0 to 8 pods.
- T+02:00 — Worker peaked at ~22 pods; HPA scaled API replicas on sustained k6 load.
- T+05:00 — Queue draining; KEDA cooldown began.
- T+10:00 — Worker back to 0; cost SLI returned to baseline.

During the spike, open X-Ray console and Tempo dashboard;
confirm both show the same traceId for sampled traces
(reservoir 10/s + 5% fixed rate). Screenshot one trace
in each tool showing the same traceId — attach to PR.

## loadtest-author Skill audit (W6 D5 Task 4)

The `loadtest-author` Skill was run against
`multistate-api/summarize-nexus` with synthetic
placeholders (account 279566174801, region us-east-1,
synthetic tenant `tenant-synth`). Output is in
`scratch/loadtest-author-output.k6.js`.

**Accepted:** Skill suggestion to add explicit `checks: ['rate>0.99']`
threshold alongside latency and error-rate gates. Functional
correctness under load belongs in the same CI gate as tail
latency; we kept this threshold in `loadtests/multistate-api-p99.js`.

**Rejected:** Skill silently renormalised workload-mix weights
from `{0.7, 0.2, 0.05, 0.05}` to `{0.636, 0.182, 0.091, 0.091}`
instead of refusing the invalid sum. Our script preserves the
engineer's explicit `{0.7, 0.2, 0.1}` weights that sum to 1.0;
renormalisation breaks the audit trail (Topic 10 quiz #3).

**Rejected:** Skill proposed `http_req_duration: ['avg<900']` mapping
to the p99 SLO — Topic 9 ai_practice rejects avg for a p99 target.
We kept `p(99)<900` exactly.

## Runbook — when the gate fails

1. Open the workflow run. The failing step links to the
   k6-summary artefact.
2. Read which threshold failed (`http_req_duration`,
   `http_req_failed`, `cost_per_request_usd`).
3. Cross-reference the Tempo dashboard for the spike
   window; identify latency, error, or cost regression.
4. If cost: drill `acme/llmproxy/CostUsd by tenant` for
   the spike window; `tenant-synth` dominance is expected
   during load tests.
5. If latency: open the X-Ray service map; a 1.2s
   Bedrock segment is an AWS-side issue, not the app.
6. Patch on the PR branch; re-run `workflow_dispatch`;
   gate goes green; merge.

## W7 readiness cross-links

W7 capstone runs three readiness checks against this substrate:

1. **SLO budget** — p99 ≤ 900 ms and error rate ≤ 0.005 sustained
   over the W7 observation window; k6 gate thresholds are the contract.
2. **Cost-per-request budget** — p95 cost ≤ 0.006 USD/request;
   W6D4 EMF + W6D5 load gate enforce the same number.
3. **AI-tool audit** — every SRE doc cites one accepted and one
   rejected Skill suggestion with reasons (this section).

Production-readiness checklist template:
[Week 7 production-readiness checklist](https://github.com/AI-Native-2026-05-26-Intuit/teaching-material/blob/main/week07/assignments/production-readiness-checklist.md)
