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

Run: `COUNT=4000 ./scripts/spike-runner.sh` (batched SendMessageBatch).

### Actual run — 2026-07-14 (account `279566174801`, queue `multistate-nexus-jobs-dev`)

| Marker | UTC | Observation |
|--------|-----|-------------|
| T+00:00 | 08:06:50Z | Spike started. Pre-spike visible depth **2487**. |
| T+00:55 | 08:07:45Z≈ | `posted 250 / 4000` |
| T+04:00 | 08:10:50Z≈ | `posted 1000 / 4000` |
| T+08:00 | 08:14:50Z≈ | `posted 2000 / 4000` |
| T+11:00 | 08:17:50Z≈ | `posted 3000 / 4000` |
| T+14:33 | 08:21:23Z | Spike **exit 0**. Post-spike visible depth **6387** (Δ ≈ +3900). |
| KEDA target | — | At `queueLength: "10"`, depth 6387 ⇒ `ceil(6387/10)=639` ideal → **capped at `maxReplicaCount: 28`**. |

```text
$ COUNT=4000 ./scripts/spike-runner.sh
Posting 4000 synthetic events to .../multistate-nexus-jobs-dev
  posted 250 / 4000
  ...
  posted 4000 / 4000
Spike posted.   # exit 0 at 2026-07-14T08:21:23Z

$ aws sqs get-queue-attributes ... ApproximateNumberOfMessages
pre:  2487
post: 6387
```

### Scaler / node observation (local `k3d-multistate-dev`)

KEDA Helm chart was installed and CRDs (`scaledobjects.keda.sh`) are present, but the operator
pods stay in **ImagePullBackOff** (`ghcr.io` TLS MITM inside the k3d nodes —
`x509: certificate signed by unknown authority`). Without a healthy
`keda-operator`, ScaledObject reconciliation and worker 0→28 scale-out could
not be observed on this laptop cluster.

Karpenter NodePool cannot provision EC2 nodes on k3d (no EC2NodeClass /
cloud controller). HPA API replica growth was not exercised by this SQS-only
spike (HPA targets `multistate_inflight_requests` on the API Deployment;
pair with the k6 load gate for that signal).

### X-Ray sampling rule (live)

`multistate-observability-dev` is **CREATE_COMPLETE**:

```text
$ aws xray get-sampling-rules --region us-east-1 \
    --query 'SamplingRuleRecords[?SamplingRule.RuleName==`multistate-api-default`].SamplingRule.[RuleName,ReservoirSize,FixedRate,ServiceName]' \
    --output text
multistate-api-default	10	0.05	multistate-api
```

### X-Ray + Tempo same-traceId screenshot

**Blocked on this environment:** `aws xray get-trace-summaries` for
`service("multistate-api")` returned **0** traces in the last 24h. Local k3d
has no Tempo / ADOT dual-exporter Running (ADOT image pull + IRSA role
`adot-collector-xray-write` need the cohort EKS path). Until ADOT is
exporting OTLP→Tempo **and** awsxray, there is no shared 16-byte traceId to
screenshot in both UIs.

**Unblock checklist (ES / cohort cluster):**

1. Sync config-repo W6D5 manifests (ADOT collector + KEDA ScaledObject) via Argo CD on a cluster that can pull `ghcr.io` / ECR and assume IRSA.
2. Confirm `kubectl -n multistate-dev logs -l app=adot-collector` shows `Exporters: [otlphttp/tempo awsxray]`.
3. Hit `GET /tenants/tnt_synth_001` once; copy `traceId` from Tempo Explore; paste into X-Ray console → screenshot pair for the PR.

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
