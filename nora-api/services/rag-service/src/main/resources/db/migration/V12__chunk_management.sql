-- Dify 级知识库能力(2026-09-22,阶段 D:分段管理 + 配置化 + 元数据)。
--
-- 1) 分段级管理:分段可编辑/启停/删除/手动新增——Dify 的核心 UX。
--    - enabled:  分段停用(退出检索,保留内容;与文档停用同语义)
--    - edited:   人工编辑过(重新分段时默认保留?首版:重新分段覆盖,编辑标记
--                让 UI 提示"此段已人工修改")
--    - origin:   auto(自动分段)/ manual(手动新增)
-- 2) 文档级分段配置:chunk_config 存分段时的参数快照(JSON:
--    {mode, chunkSize, overlap})——重新分段可换参数,UI 显示当前配置。
-- 3) 库级索引/检索配置:retrieval_config JSON(检索模式/权重/阈值/重排)——
--    不同库可用不同策略(如 FAQ 库用纯关键词、长文档库开重排)。
-- 4) 文档元数据:metadata JSON(自定义键值;检索过滤的有限字段)。

ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS edited BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS origin VARCHAR(20) NOT NULL DEFAULT 'auto';

ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS chunk_config TEXT;

ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS retrieval_config TEXT;

ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS metadata TEXT;

CREATE INDEX IF NOT EXISTS idx_chunk_enabled ON knowledge_chunk (doc_id) WHERE enabled AND deleted_at IS NULL;

COMMENT ON COLUMN knowledge_chunk.enabled IS '分段停用=退出检索(保留内容;Dify 同款分段开关)';
COMMENT ON COLUMN knowledge_chunk.edited IS '人工编辑过(重新分段会覆盖,UI 提示)';
COMMENT ON COLUMN knowledge_chunk.origin IS 'auto=自动分段;manual=手动新增';
COMMENT ON COLUMN knowledge_doc.chunk_config IS '分段参数快照 JSON:{mode,chunkSize,overlap}';
COMMENT ON COLUMN knowledge_base.retrieval_config IS '库级检索配置 JSON:{mode,topK,minScore,vectorWeight,keywordWeight,rerankEnabled}';
COMMENT ON COLUMN knowledge_doc.metadata IS '文档自定义元数据 JSON(键值;检索过滤字段)';
