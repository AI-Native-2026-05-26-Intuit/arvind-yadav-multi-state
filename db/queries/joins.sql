-- db/queries/joins.sql
-- Task 1.1 - JOINs across multistate.tenant (parent) and multistate.allocation (child).
-- Run with:
--     psql -h localhost -U postgres -d postgres -f db/queries/joins.sql

SET search_path TO multistate, public;

-- 1. INNER JOIN: every allocation paired with its tenant's legal_name and status.
--    Rows where a tenant has no allocations are excluded by definition.
SELECT t.id              AS tenant_id,
       t.legal_name,
       t.status,
       a.jurisdiction_code,
       a.tax_year,
       a.amount
FROM   multistate.tenant     t
JOIN   multistate.allocation a ON a.tenant_id = t.id
ORDER  BY t.id, a.amount DESC;

-- 2. LEFT JOIN: every tenant, including those with zero allocations
--    (COUNT on the right-side key yields 0 instead of 1 for NULLs).
SELECT t.id              AS tenant_id,
       t.legal_name,
       COUNT(a.id)       AS allocation_count
FROM   multistate.tenant     t
LEFT  JOIN multistate.allocation a ON a.tenant_id = t.id
GROUP  BY t.id, t.legal_name
ORDER  BY allocation_count DESC, t.id;
