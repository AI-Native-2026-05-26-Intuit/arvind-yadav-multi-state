-- multistate-ai/sql/V001__doc_chunks.sql
-- Apply once per database. The extension exists from W6 D4 already.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS doc_chunks (
    chunk_id      BIGSERIAL PRIMARY KEY,
    doc_id        TEXT NOT NULL,
    chunk_idx     INTEGER NOT NULL,
    chunk_text    TEXT NOT NULL,
    embedding     vector(384) NOT NULL,
    model_version TEXT NOT NULL,
    tenant_id     TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The idempotency key the loader's ON CONFLICT clause keys on.
    UNIQUE (doc_id, chunk_idx, model_version)
);

CREATE INDEX IF NOT EXISTS doc_chunks_doc_id_idx
    ON doc_chunks (doc_id);

CREATE INDEX IF NOT EXISTS doc_chunks_tenant_model_idx
    ON doc_chunks (tenant_id, model_version);

-- HNSW index; op-class MUST match the operator used at query time (<=>).
CREATE INDEX IF NOT EXISTS doc_chunks_embedding_hnsw
    ON doc_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
