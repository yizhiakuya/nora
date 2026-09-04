-- Deduplication key: knowledge_doc rows are keyed by (source, source_id) so
-- re-indexing the same source entity replaces its own row instead of silently
-- evicting an unrelated doc that happens to share the same display name.
-- Phase 1 only populates it for source='file' (source_id = file-service fileId);
-- other ingestion sources leave it NULL and are keyed by name.

ALTER TABLE knowledge_doc ADD COLUMN source_id BIGINT;
CREATE UNIQUE INDEX idx_doc_source ON knowledge_doc (source, source_id) WHERE source_id IS NOT NULL;
