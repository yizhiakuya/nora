-- 表/列注释(schema_rag):Schema 浏览页直接展示。
-- COMMENT ON 幂等,重复执行安全。

COMMENT ON TABLE knowledge_doc IS '知识库文档:文件解析分块后向量化入库';
COMMENT ON COLUMN knowledge_doc.id IS '主键';
COMMENT ON COLUMN knowledge_doc.name IS '文档名';
COMMENT ON COLUMN knowledge_doc.source IS '来源(上传/对话保存等)';
COMMENT ON COLUMN knowledge_doc.chunks IS '分块数';
COMMENT ON COLUMN knowledge_doc.status IS '索引状态:indexed/indexing/error';
COMMENT ON COLUMN knowledge_doc.size IS '展示用大小';
COMMENT ON COLUMN knowledge_doc.quality IS '质量评分';
COMMENT ON COLUMN knowledge_doc.updated_at IS '更新时间';
COMMENT ON COLUMN knowledge_doc.source_id IS '源文件 id(file-service)';
COMMENT ON COLUMN knowledge_doc.deleted_at IS '软删除时间;NULL=存活';

COMMENT ON TABLE knowledge_chunk IS '知识库分块:正文 + jina-embeddings-v3 向量(1024 维,pending 时 NULL)';
COMMENT ON COLUMN knowledge_chunk.id IS '主键';
COMMENT ON COLUMN knowledge_chunk.doc_id IS '所属文档';
COMMENT ON COLUMN knowledge_chunk.chunk_index IS '块序号(文档内)';
COMMENT ON COLUMN knowledge_chunk.content IS '块正文';
COMMENT ON COLUMN knowledge_chunk.embedding IS '向量(1024 维;NULL=待重试)';
COMMENT ON COLUMN knowledge_chunk.token_count IS 'token 估算数';
COMMENT ON COLUMN knowledge_chunk.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_chunk.deleted_at IS '软删除时间;NULL=存活';
