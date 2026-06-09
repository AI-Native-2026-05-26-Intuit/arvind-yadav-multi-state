-- db/queries/window.sql
-- Task 1.3 - Window functions.
-- Run with:
--     psql -h localhost -U postgres -d postgres -f db/queries/window.sql
--
-- Window functions return one row per input row plus an aggregate column —
-- unlike GROUP BY, they do not collapse the result set.

SET search_path TO multistate, public;

-- For each tenant, RANK allocations by amount (highest = 1) and also
-- expose the tenant-wide total alongside every child row.
SELECT t.id                AS tenant_id,
       t.legal_name,
       a.jurisdiction_code,
       a.tax_year,
       a.amount,
       RANK() OVER (PARTITION BY t.id ORDER BY a.amount DESC) AS amount_rank,
       SUM(a.amount) OVER (PARTITION BY t.id)                  AS tenant_total
FROM   multistate.tenant     t
JOIN   multistate.allocation a ON a.tenant_id = t.id
ORDER  BY t.id, amount_rank;
