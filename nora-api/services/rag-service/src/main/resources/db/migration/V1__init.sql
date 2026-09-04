-- rag-service schema (schema_rag), per architecture-v2.md section 7.1 (one schema per service)
-- and nora-api-initiation-2026-09-04.md section 4.1 (knowledge_doc / knowledge_chunk DDL).
--
-- NOTE on vector dimensionality: the initiation doc specifies vector(1536) (OpenAI
-- text-embedding-3-small). The actual embedding provider is Jina AI jina-embeddings-v3,
-- whose maximum output dimension is 1024 — this migration therefore declares vector(1024).
-- Known, accepted deviation; switching back to an OpenAI model requires a new migration.

CREATE TABLE knowledge_doc (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    chunks     INTEGER DEFAULT 0,
    status     VARCHAR(20) DEFAULT 'processing',
    size       VARCHAR(20),
    quality    INTEGER DEFAULT 0,
    updated_at TIMESTAMP DEFAULT now()
);

CREATE TABLE knowledge_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT REFERENCES schema_rag.knowledge_doc (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content     TEXT,
    embedding   vector(1024),
    token_count INTEGER,
    created_at  TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_chunk_hnsw ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_doc ON knowledge_chunk (doc_id);
