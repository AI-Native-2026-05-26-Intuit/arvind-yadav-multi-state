-- multistate-ai/sql/V002__rag2_metadata_and_partial_indexes.sql
-- Applied online; safe with concurrent reads. Existing rows remain valid.
-- All indexes below are created CONCURRENTLY (must run outside a transaction).

ALTER TABLE doc_chunks
  ADD COLUMN IF NOT EXISTS chunk_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS content_hash   text;

-- GIN index for arbitrary JSONB key/value filtering (Topic 9).
-- jsonb_path_ops is smaller and faster than the default jsonb_ops for
-- the path-equality containment operator (@>) the application uses.
CREATE INDEX CONCURRENTLY IF NOT EXISTS doc_chunks_metadata_gin
  ON doc_chunks USING gin (chunk_metadata jsonb_path_ops);

-- Per-tenant partial HNSW indexes for tenant isolation. The pre-filter
-- on tenant_id hits the per-tenant index instead of the giant
-- cross-tenant HNSW; recall and latency match the unfiltered case.
CREATE INDEX CONCURRENTLY IF NOT EXISTS doc_chunks_tenant_a_hnsw
  ON doc_chunks USING hnsw (embedding vector_cosine_ops)
  WITH (m = 24, ef_construction = 128)
  WHERE tenant_id = 'tenant-a';

CREATE INDEX CONCURRENTLY IF NOT EXISTS doc_chunks_tenant_b_hnsw
  ON doc_chunks USING hnsw (embedding vector_cosine_ops)
  WITH (m = 24, ef_construction = 128)
  WHERE tenant_id = 'tenant-b';

CREATE INDEX CONCURRENTLY IF NOT EXISTS doc_chunks_tenant_c_hnsw
  ON doc_chunks USING hnsw (embedding vector_cosine_ops)
  WITH (m = 24, ef_construction = 128)
  WHERE tenant_id = 'tenant-c';

-- Postgres FTS column + GIN index for the BM25-shaped sparse retriever
-- (Topic 6 fallback path when OpenSearch is not available).
ALTER TABLE doc_chunks
  ADD COLUMN IF NOT EXISTS chunk_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED;

CREATE INDEX CONCURRENTLY IF NOT EXISTS doc_chunks_tsv_gin
  ON doc_chunks USING gin (chunk_tsv);
