# Query catalogue

Four standalone SQL files exercise the Day 1 schema (`multistate.tenant`,
`multistate.allocation`, `multistate.jurisdiction`). Each file is runnable
end-to-end against a freshly seeded database.

## Query catalogue

- **`joins.sql`** — "Which allocations belong to which tenant, and which
  tenants have zero allocations?" Touches `multistate.tenant` and
  `multistate.allocation`. First an **INNER JOIN** pairs every allocation
  with its tenant's legal name and status; the second statement uses a
  **LEFT JOIN** + `COUNT(a.id)` so tenants with no children surface with a
  count of `0` instead of being dropped.
- **`cte.sql`** — "Which tenants have total allocated amount above
  $100.00?" Touches `multistate.tenant` and `multistate.allocation`. Uses
  a **CTE** (`WITH totals AS …`) to pre-aggregate `SUM(amount)` per tenant,
  then joins the named relation back to the parent and filters by
  threshold.
- **`window.sql`** — "Per tenant, rank each allocation by amount and
  display the tenant-wide total beside every row." Touches
  `multistate.tenant` and `multistate.allocation`. Uses **window
  functions** (`RANK() OVER (PARTITION BY tenant_id ORDER BY amount DESC)`
  and `SUM(amount) OVER (PARTITION BY tenant_id)`) so every input row
  survives — no collapse.
- **`group_by_having.sql`** — "Which tenants have three or more
  allocations, ranked by average amount?" Touches `multistate.tenant` and
  `multistate.allocation`. Uses **GROUP BY** with a **HAVING** predicate
  on `COUNT(a.*) >= 3` — group-level filtering that `WHERE` cannot
  express.

## Running locally

Apply the schema and seed, then run any single query file:

```bash
# Apply schema and seed (run once per fresh database)
psql -h localhost -U postgres -d postgres -f db/V1__schema.sql
psql -h localhost -U postgres -d postgres -f db/V2__seed.sql

# Run any one of the query files
psql -h localhost -U postgres -d postgres -f db/queries/joins.sql
psql -h localhost -U postgres -d postgres -f db/queries/cte.sql
psql -h localhost -U postgres -d postgres -f db/queries/window.sql
psql -h localhost -U postgres -d postgres -f db/queries/group_by_having.sql
```

Each query file sets `search_path TO multistate, public` at the top, so no
schema-qualification is needed at the shell.

## Running in tests

```bash
./gradlew test --tests "*QueryIT"
```

Testcontainers spins up a `postgres:16-alpine` container, applies
`V1__schema.sql` + `V2__seed.sql`, runs the queries against it, and tears
the container down — no manual `docker run` needed.

## Trade-offs

**CTE vs subquery in `cte.sql`.** The aggregate
`SELECT tenant_id, SUM(amount) … GROUP BY tenant_id` could be inlined as a
subquery in the `FROM` clause and modern Postgres' planner would treat
both forms identically. We chose a **CTE** because the named `totals`
relation reads as a discrete step ("first, sum allocations per tenant"),
is trivially reusable if the outer query ever needs to reference it
twice, and keeps the final `SELECT` focused on the join and filter rather
than nested aggregation. The win here is readability and refactorability,
not performance.

**Window vs GROUP BY in `window.sql`.** `GROUP BY` collapses each group
into a single row — it cannot return both the per-allocation detail rows
*and* the per-tenant aggregate in the same result set. The window
functions `RANK() OVER (PARTITION BY tenant_id …)` and
`SUM(amount) OVER (PARTITION BY tenant_id)` compute the same aggregates
but project them alongside every input row, preserving detail. Replacing
this with `GROUP BY` would require a self-join back to `allocation` to
restore the detail rows, which is strictly more code and more work for
the planner.
