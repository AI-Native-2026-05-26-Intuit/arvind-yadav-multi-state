-- src/main/resources/db/migration/V3__create_tenant_embeddings.sql
--
-- pgvector extension + tenant_embeddings table for the
-- tenant-similarity feature
-- (summarize-nexus). Lives in the W6 D3 RDS database from the
-- multistate-app-dev stack. Index choice and operator are pinned;
-- mismatching index op-class to query operator forces a seq scan
-- and ruins latency on any table over a few thousand rows.

-- Enable once per database. Idempotent.
CREATE EXTENSION IF NOT EXISTS vector;

-- The embeddings table. vector(1024) matches the
-- amazon.titan-embed-text-v2:0 output dimensionality; switching models
-- is a data migration, not a property change.
CREATE TABLE IF NOT EXISTS tenant_embeddings (
    id              UUID         PRIMARY KEY,
    tenant_id       TEXT         NOT NULL,
    embedding       vector(1024) NOT NULL,
    inserted_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- HNSW chosen because:
--   * inserts are interleaved with queries (continuous ingest)
--   * recall-95 latency must stay under ~50ms p99 on db.r6g.xlarge
--   * memory footprint is acceptable at projected row counts
-- IVFFlat would require a representative pre-load we don't have yet.
--
-- vector_cosine_ops matches the <=> operator the JDBC query uses;
-- mixing op-class with operator forces a sequential scan.
CREATE INDEX IF NOT EXISTS tenant_embeddings_hnsw
    ON tenant_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- B-tree on tenant_id so the planner can intersect the HNSW
-- order-by with the WHERE tenant_id = $1 filter for tenant
-- isolation without a full table scan.
CREATE INDEX IF NOT EXISTS tenant_embeddings_tenant
    ON tenant_embeddings (tenant_id);
