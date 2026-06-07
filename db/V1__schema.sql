-- V1__schema.sql
-- Initial schema for the UptimeCrew multi-state capstone.
-- All objects live in the `multistate` schema — never `public` — so the
-- compliance domain stays cleanly separated from anything else that might
-- get loaded into the same database.

CREATE SCHEMA IF NOT EXISTS multistate;
SET search_path TO multistate, public;


-- =====================================================================
-- multistate.jurisdiction — reference table of US states (and DC).
-- Created first because `tenant` and `allocation` both FK into it.
-- =====================================================================
CREATE TABLE multistate.jurisdiction (
    -- 2-letter USPS state code. TEXT (not CHAR(2)) so we control length
    -- via CHECK and stay consistent with TEXT-everywhere id policy.
    code             TEXT        PRIMARY KEY,

    -- — intent —
    -- Display name must be unique so two states can't claim "California".
    display_name     TEXT        NOT NULL UNIQUE,

    -- — intent —
    -- Postal codes are exactly 2 uppercase letters; enforce at the DB so
    -- bad seed data (e.g. "Cal", "ca") can never land in the table.
    CONSTRAINT jurisdiction_code_shape
        CHECK (code ~ '^[A-Z]{2}$'),

    -- — intent —
    -- Display name must be non-blank — required by the domain rule that
    -- every jurisdiction is human-presentable in UI / filings.
    CONSTRAINT jurisdiction_display_name_not_blank
        CHECK (length(trim(display_name)) > 0),

    -- Does this state levy a personal income tax? Drives whether an
    -- allocation row in this state actually needs a filing.
    has_income_tax   BOOLEAN     NOT NULL,

    -- Participates in any reciprocity agreement with a neighboring state.
    is_reciprocal    BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- =====================================================================
-- multistate.tenant — one row per taxpayer entity (the platform's customer).
-- =====================================================================
CREATE TABLE multistate.tenant (
    -- Prefixed synthetic key, e.g. "ten_<uuid>". TEXT, not SERIAL — IDs
    -- are domain values, never auto-incrementing integers.
    id                          TEXT        PRIMARY KEY,

    -- — intent —
    -- Legal name must be present and non-blank: it appears on every
    -- filing artifact we produce, so an empty string is never valid.
    legal_name                  TEXT        NOT NULL
        CHECK (length(trim(legal_name)) > 0),

    -- — intent —
    -- A tenant's home state must reference a real jurisdiction row.
    -- RESTRICT (not CASCADE) — jurisdictions are reference data; deleting
    -- one while tenants still claim it as home would silently corrupt
    -- their compliance posture.
    primary_jurisdiction_code   TEXT        NOT NULL
        REFERENCES multistate.jurisdiction(code) ON DELETE RESTRICT,

    -- — intent —
    -- Entity lifecycle stage. Modeled as TEXT + CHECK rather than a
    -- native ENUM because altering a Postgres ENUM is a migration
    -- headache; adding a new status here is a one-line ALTER … CHECK.
    status                      TEXT        NOT NULL
        CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),

    incorporated_on             DATE        NOT NULL,

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- — intent —
    -- updated_at must never precede created_at — guards against bad
    -- backfills or clock-skewed writers from corrupting audit order.
    CONSTRAINT tenant_updated_after_created
        CHECK (updated_at >= created_at)
);


-- =====================================================================
-- multistate.allocation — computed income allocations, one row per
-- (tenant, jurisdiction, tax_year, allocated_for). This is the
-- persistence form of the Week 1 `IncomeAllocation` record.
-- =====================================================================
CREATE TABLE multistate.allocation (
    -- Prefixed synthetic key, e.g. "alloc_<uuid>".
    id                  TEXT          PRIMARY KEY,

    -- — intent —
    -- Allocations are strict children of a tenant; if the tenant is
    -- removed, their allocations should go too (CASCADE).
    tenant_id           TEXT          NOT NULL
        REFERENCES multistate.tenant(id) ON DELETE CASCADE,

    -- — intent —
    -- Jurisdiction is reference data, not a parent in the lifecycle
    -- sense, so RESTRICT — never orphan an allocation by deleting its
    -- state out from under it.
    jurisdiction_code   TEXT          NOT NULL
        REFERENCES multistate.jurisdiction(code) ON DELETE RESTRICT,

    -- — intent —
    -- Tax year must be in a plausible range. 1900 picks up any historical
    -- amendment; 2100 is a sanity upper bound that will outlast this codebase.
    tax_year            INTEGER       NOT NULL
        CHECK (tax_year BETWEEN 1900 AND 2100),

    -- The pay-period / filing-period date this allocation covers.
    allocated_for       DATE          NOT NULL,

    -- — intent —
    -- Money is always NUMERIC(12,2) — never FLOAT/REAL/DOUBLE. The
    -- IncomeAllocation record guarantees non-negative scale-2 amounts;
    -- the DB enforces the same invariant so direct SQL writes can't
    -- bypass it.
    amount              NUMERIC(12,2) NOT NULL
        CHECK (amount >= 0),

    -- — intent —
    -- Which AllocationStrategy produced this row. Stored as TEXT + CHECK
    -- so adding a new strategy is a one-line migration (no ENUM ALTER).
    strategy_name       TEXT          NOT NULL
        CHECK (strategy_name IN (
            'EQUAL_SPLIT',
            'WEIGHTED_DAY_COUNT',
            'PRIMARY_ONLY'
        )),

    computed_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- — intent —
    -- A tenant should have exactly one allocation per
    -- (jurisdiction, tax_year, allocated_for). The natural key prevents
    -- duplicate rows from re-running a strategy by accident.
    CONSTRAINT allocation_natural_key
        UNIQUE (tenant_id, jurisdiction_code, tax_year, allocated_for)
);
