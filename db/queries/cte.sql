-- db/queries/cte.sql
-- Task 1.2 - Common Table Expression.
-- Run with:
--     psql -h localhost -U postgres -d postgres -f db/queries/cte.sql
--
-- Why a CTE and not a subquery: the named "totals" relation reads as a
-- standalone step ("first, sum allocations per tenant"), can be reused
-- across multiple references in the outer query, and keeps the final
-- SELECT focused on the join + filter rather than nested aggregation.
-- For a one-off, single-use case the planner treats both forms the same
-- in modern Postgres; the win here is readability and refactorability.

SET search_path TO multistate, public;

-- "totals" sums allocation amounts per tenant; the outer SELECT joins it
-- back to surface only tenants whose total exceeds the threshold.
WITH totals AS (
    SELECT tenant_id,
           SUM(amount) AS total_amount
    FROM   multistate.allocation
    GROUP  BY tenant_id
)
SELECT t.id           AS tenant_id,
       t.legal_name,
       t.status,
       tot.total_amount
FROM   multistate.tenant t
JOIN   totals            tot ON tot.tenant_id = t.id
WHERE  tot.total_amount > 100.00
ORDER  BY tot.total_amount DESC;
