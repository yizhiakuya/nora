-- 软删除:knowledge_doc / knowledge_chunk 改 UPDATE 标记删除(2026-09-12 用户要求,全库统一方案)。
-- deleted_at IS NULL = 存活;有值 = 删除时间。
--
-- 去重键重建:软删的旧文档不再占用 (source, source_id) / (source, name) 键——
-- 否则「重传同名文件」会撞唯一约束(旧行还在,只是已删除)。partial unique
-- index 增加 deleted_at IS NULL 条件。
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

DROP INDEX IF EXISTS schema_rag.idx_doc_source;
CREATE UNIQUE INDEX idx_doc_source ON schema_rag.knowledge_doc (source, source_id)
    WHERE source_id IS NOT NULL AND deleted_at IS NULL;

DROP INDEX IF EXISTS schema_rag.idx_doc_source_name;
CREATE UNIQUE INDEX idx_doc_source_name ON schema_rag.knowledge_doc (source, name)
    WHERE source_id IS NULL AND deleted_at IS NULL;

-- 检索/统计按 doc_id + 存活过滤:给 chunk 的存活扫描加辅助索引
CREATE INDEX IF NOT EXISTS idx_chunk_doc_live ON knowledge_chunk (doc_id) WHERE deleted_at IS NULL;
