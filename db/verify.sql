-- verify.sql
-- Read-only sanity checks for the multistate seed. Run after V1+V2.
-- Each query prints a heading so the output is easy to scan.

SET search_path TO multistate, public;


-- ---------------------------------------------------------------------
-- (1) Plain row counts — quick smoke test that every table got seeded.
-- ---------------------------------------------------------------------
\echo '--- (1) row counts per table ---'
SELECT 'jurisdiction' AS table_name, COUNT(*) AS row_count FROM multistate.jurisdiction
UNION ALL
SELECT 'tenant',                     COUNT(*)              FROM multistate.tenant
UNION ALL
SELECT 'allocation',                 COUNT(*)              FROM multistate.allocation
ORDER BY table_name;


-- ---------------------------------------------------------------------
-- (2) JOIN: every allocation paired with its tenant's legal name and
-- the jurisdiction's display name. Confirms both FKs resolve and the
-- natural-key uniqueness holds (no duplicate composite rows).
-- ---------------------------------------------------------------------
\echo '--- (2) allocations joined to tenant + jurisdiction ---'
SELECT
    a.id              AS allocation_id,
    t.legal_name      AS tenant,
    j.display_name    AS jurisdiction,
    a.tax_year,
    a.allocated_for,
    a.amount,
    a.strategy_name
FROM multistate.allocation  a
JOIN multistate.tenant       t ON t.id   = a.tenant_id
JOIN multistate.jurisdiction j ON j.code = a.jurisdiction_code
ORDER BY a.allocated_for, a.id;


-- ---------------------------------------------------------------------
-- (3) Aggregate: total allocated amount per jurisdiction per tax year.
-- Exercises GROUP BY across a JOIN — the kind of query a state-filing
-- summary report would need.
-- ---------------------------------------------------------------------
\echo '--- (3) total allocated amount per jurisdiction per tax year ---'
SELECT
    j.code                       AS jurisdiction,
    j.display_name,
    a.tax_year,
    COUNT(*)                     AS allocation_count,
    SUM(a.amount)                AS total_amount
FROM multistate.allocation  a
JOIN multistate.jurisdiction j ON j.code = a.jurisdiction_code
GROUP BY j.code, j.display_name, a.tax_year
ORDER BY a.tax_year, total_amount DESC;
