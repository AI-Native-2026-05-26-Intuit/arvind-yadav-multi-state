# Loki label contract — multistate-api

Onboarding reference for Week 5 Day 5 structured logging. Loki indexes **labels**, not log line bodies. Every label you add becomes a separate stream; high-cardinality labels explode storage cost and query latency.

## Permitted Loki labels (exactly four)

| Label | Example | Source |
|-------|---------|--------|
| `app` | `multistate-api` | Logstash `customFields` in `logback-spring.xml` |
| `env` | `k8s` | Logstash `customFields` |
| `level` | `info` | Logback level (Alloy / Promtail pipeline) |
| `pod` | `multistate-api-7f8b9c-xyz` | Kubernetes metadata from the log collector |

These four are enough to slice traffic by service, environment, severity, and replica.

## Forbidden as Loki labels (log body only)

| Identifier | Why forbidden | Where it lives instead |
|------------|---------------|------------------------|
| `tenantId` | Unbounded — one stream per tenant | Log message field, trace attributes |
| `correlationId` | Unbounded — one stream per request | Top-level JSON field from MDC (`LogstashEncoder`) |
| User id (JWT `sub`) | Unbounded — one stream per user | Log message / trace attributes, never labels |

**Rule:** if the value is unique per request or per user, it belongs in the **JSON log line**, not in Loki labels.

## Example queries (Grafana Explore → Loki)

```logql
{app="multistate-api"} |= "lookup"
{app="multistate-api", level="error"}
{app="multistate-api", env="k8s"} | json | correlationId="550e8400-e29b-41d4-a716-446655440000"
```

Expect each line to be a single JSON object with `trace_id`, `span_id`, and `correlationId` when the OTel agent and correlation filter are active.

## Why this matters

Promoting `tenantId` or `correlationId` to a Loki label creates **millions of streams** — Loki’s index is per-label-set, not full-text. Queries slow down, retention costs spike, and the observability stack becomes the outage. Keeping identifiers in the log body preserves full-text search (`|= "lookup"`) without cardinality bombs.
