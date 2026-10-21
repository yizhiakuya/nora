-- 先构建后发布(2026-09-22,知识库优化阶段 A)。
--
-- 问题:indexDocument 此前先软删旧文档再插入新版并嵌入——嵌入失败时旧版
-- 也退出了检索(方案 §3.2 P0)。改为「新行构建 → 校验 → 短事务切换发布」。
--
-- 支持结构:去重键只在**已发布(indexed)**行上生效——
--   - processing 行(构建中)不占键 → 旧 indexed 行可继续存活/被检索;
--   - 切换发布时:软删旧 indexed 行 + 新行改 indexed,同一短事务内完成;
--   - failed 行(构建失败)不占键 → 旧版保持可用,失败行供用户重试/查看。
--
-- 检索侧同步收窄:只检索 status='indexed' 且 deleted_at IS NULL 的文档
-- (见 RetrievalService SQL)——failed/processing 行的 chunks 不参与召回。
DROP INDEX IF EXISTS schema_rag.idx_doc_source;
CREATE UNIQUE INDEX idx_doc_source ON schema_rag.knowledge_doc (source, source_id)
    WHERE source_id IS NOT NULL AND deleted_at IS NULL AND status = 'indexed';

DROP INDEX IF EXISTS schema_rag.idx_doc_source_name;
CREATE UNIQUE INDEX idx_doc_source_name ON schema_rag.knowledge_doc (source, name)
    WHERE source_id IS NULL AND deleted_at IS NULL AND status = 'indexed';
