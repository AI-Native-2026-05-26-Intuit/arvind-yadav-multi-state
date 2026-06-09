-- db/queries/group_by_having.sql
-- Task 1.4 - GROUP BY + HAVING.
-- Run with:
--     psql -h localhost -U postgres -d postgres -f db/queries/group_by_having.sql
--
-- HAVING filters at the group level using an aggregate predicate. If the
-- predicate can be expressed without an aggregate, it belongs in WHERE,
-- not HAVING.

SET search_path TO multistate, public;

-- Tenants with 3 or more allocations, ranked by their average amount.
SELECT t.id              AS tenant_id,
       t.legal_name,
       t.status,
       COUNT(a.*)        AS allocation_count,
       AVG(a.amount)     AS avg_amount
FROM   multistate.tenant     t
JOIN   multistate.allocation a ON a.tenant_id = t.id
GROUP  BY t.id, t.legal_name, t.status
HAVING COUNT(a.*) >= 3
ORDER  BY avg_amount DESC;
