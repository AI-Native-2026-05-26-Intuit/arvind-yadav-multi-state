-- V2__seed.sql
-- Synthetic seed data for the multistate capstone.
-- All identifiers are placeholder forms (tenant-a, alloc-2026-0001,
-- multistate.example.internal) — no real customer or product names.

SET search_path TO multistate, public;

BEGIN;

-- ---------------------------------------------------------------------
-- jurisdiction — reference rows. Real USPS codes (these are public data,
-- not "real customer names") but the income-tax flags are the only
-- behavior-bearing values.
-- ---------------------------------------------------------------------
INSERT INTO multistate.jurisdiction (code, display_name, has_income_tax, is_reciprocal) VALUES
    ('CA', 'California',  TRUE,  FALSE),
    ('NY', 'New York',    TRUE,  TRUE),
    ('TX', 'Texas',       FALSE, FALSE),
    ('WA', 'Washington',  FALSE, FALSE),
    ('NJ', 'New Jersey',  TRUE,  TRUE),
    ('PA', 'Pennsylvania',TRUE,  TRUE);

-- ---------------------------------------------------------------------
-- tenant — synthetic taxpayer entities. Names use the
-- "tenant-<letter>.example.internal" pattern from the curriculum.
-- ---------------------------------------------------------------------
INSERT INTO multistate.tenant
    (id, legal_name, primary_jurisdiction_code, status, incorporated_on)
VALUES
    ('ten-a', 'tenant-a.example.internal', 'CA', 'ACTIVE',    DATE '2018-01-15'),
    ('ten-b', 'tenant-b.example.internal', 'NY', 'ACTIVE',    DATE '2019-06-01'),
    ('ten-c', 'tenant-c.example.internal', 'TX', 'SUSPENDED', DATE '2020-03-22'),
    ('ten-d', 'tenant-d.example.internal', 'NJ', 'ACTIVE',    DATE '2021-11-09'),
    ('ten-e', 'tenant-e.example.internal', 'WA', 'CLOSED',    DATE '2017-07-30'),
    ('ten-f', 'tenant-f.example.internal', 'PA', 'ACTIVE',    DATE '2022-02-14');

-- ---------------------------------------------------------------------
-- allocation — computed allocations. Identifiers use the
-- "alloc-<year>-<seq>" form from the curriculum.
-- ---------------------------------------------------------------------
INSERT INTO multistate.allocation
    (id, tenant_id, jurisdiction_code, tax_year, allocated_for, amount, strategy_name)
VALUES
    ('alloc-2026-0001', 'ten-a', 'CA', 2026, DATE '2026-03-31', 12500.00, 'EQUAL_SPLIT'),
    ('alloc-2026-0002', 'ten-a', 'NY', 2026, DATE '2026-03-31',  7500.00, 'EQUAL_SPLIT'),
    ('alloc-2026-0003', 'ten-b', 'NY', 2026, DATE '2026-03-31', 20000.00, 'PRIMARY_ONLY'),
    ('alloc-2026-0004', 'ten-d', 'NJ', 2026, DATE '2026-03-31',  9000.00, 'WEIGHTED_DAY_COUNT'),
    ('alloc-2026-0005', 'ten-d', 'PA', 2026, DATE '2026-03-31',  4500.00, 'WEIGHTED_DAY_COUNT'),
    ('alloc-2026-0006', 'ten-f', 'PA', 2026, DATE '2026-06-30',     0.00, 'PRIMARY_ONLY'),
    ('alloc-2026-0007', 'ten-a', 'CA', 2026, DATE '2026-06-30', 13750.50, 'WEIGHTED_DAY_COUNT');

COMMIT;
