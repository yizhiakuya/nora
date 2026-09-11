-- 混合检索:关键词(pg_trgm)与向量检索并列,由 RetrievalService 融合。
-- 纯向量检索对专有名词、错误码、缩写召回弱,补一路字面匹配。
-- strict_word_similarity 用于「短查询 vs 长 chunk」场景,且按词边界匹配、不需要中文分词。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm
    ON knowledge_chunk USING gin (content gin_trgm_ops);

-- source_id IS NULL 的文档(source='text',如对话保存)此前无去重键,重复保存会堆新行。
-- 改为 (source, name) 去重,让「同名覆盖」的语义真正成立。
CREATE UNIQUE INDEX IF NOT EXISTS idx_doc_source_name
    ON knowledge_doc (source, name) WHERE source_id IS NULL;
