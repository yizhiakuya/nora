-- Nora 数据库初始化(仅首次创建数据卷时执行)
-- pgvector 扩展:rag-service 的 knowledge_chunk.embedding 需要
CREATE EXTENSION IF NOT EXISTS vector;
