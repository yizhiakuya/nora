-- 表/列注释(schema_file):Schema 浏览页直接展示。
-- COMMENT ON 幂等,重复执行安全。

COMMENT ON TABLE file_item IS '文件元数据:上传后由 Tika 解析,可触发 RAG 索引';
COMMENT ON COLUMN file_item.id IS '主键';
COMMENT ON COLUMN file_item.name IS '文件名';
COMMENT ON COLUMN file_item.file_path IS '存储路径';
COMMENT ON COLUMN file_item.mime_type IS 'MIME 类型';
COMMENT ON COLUMN file_item.size_bytes IS '文件大小(字节)';
COMMENT ON COLUMN file_item.indexed IS '是否已索引进知识库';
COMMENT ON COLUMN file_item.created_at IS '上传时间';
COMMENT ON COLUMN file_item.deleted_at IS '软删除时间;NULL=存活';
