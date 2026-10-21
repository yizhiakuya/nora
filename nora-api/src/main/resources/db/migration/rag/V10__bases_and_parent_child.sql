-- 阶段 B(2026-09-22,知识库优化):资料库分组 / 文档停用 / 分段模式 / 父子结构 / 检索记录。
--
-- 1) knowledge_base:资料库(方案 §4「默认资料库 + 可选分组」)。首版一篇文档
--    属于一个库;先建默认库把存量文档归入,普通用户不必先建库才能导入。
-- 2) knowledge_doc.base_id / enabled:归属库 + 停用开关(停用=退出检索,
--    保留数据与索引;与删除不同)。
-- 3) knowledge_doc.chunk_mode:分段模式(plain/parent_child,方案 §5.2)。
-- 4) knowledge_chunk.parent_index/parent_content:父子模式的父块(章节)——
--    子块用于匹配,父块用于提供上下文(冗余存储,个人规模可接受;
--    独立父块表会让"父块是否参与检索"变复杂)。
-- 5) retrieval_log:检索记录(方案 §6.3/§7「检索测试与记录」)——查询、
--    范围、生效配置、通道状态、结果标识与耗时,供回溯「为什么找不到」。

CREATE TABLE knowledge_base (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    is_default  BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMP
);
CREATE UNIQUE INDEX idx_base_name ON knowledge_base (name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_base_default ON knowledge_base (is_default) WHERE is_default AND deleted_at IS NULL;

INSERT INTO knowledge_base (name, description, is_default)
VALUES ('默认资料库', '未指定分组的文档自动归入', true);

ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS base_id BIGINT REFERENCES knowledge_base (id);
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS chunk_mode VARCHAR(20) NOT NULL DEFAULT 'plain';

UPDATE knowledge_doc
SET base_id = (SELECT id FROM knowledge_base WHERE is_default AND deleted_at IS NULL LIMIT 1)
WHERE base_id IS NULL;

ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS parent_index INTEGER;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS parent_content TEXT;

CREATE TABLE retrieval_log (
    id           BIGSERIAL PRIMARY KEY,
    query        TEXT NOT NULL,
    top_k        INTEGER,
    scope_json   TEXT,
    status       VARCHAR(20) NOT NULL,
    result_count INTEGER NOT NULL DEFAULT 0,
    duration_ms  BIGINT,
    detail_json  TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_retrieval_log_created ON retrieval_log (created_at DESC);

COMMENT ON TABLE knowledge_base IS '资料库分组(阶段 B;默认库收纳未分组文档)';
COMMENT ON COLUMN knowledge_doc.enabled IS '停用=false 退出检索(保留数据与索引)';
COMMENT ON COLUMN knowledge_doc.chunk_mode IS 'plain=固定长度分段;parent_child=结构父块+子块';
COMMENT ON COLUMN knowledge_chunk.parent_content IS '父子模式:所属章节(父块)完整正文';
COMMENT ON TABLE retrieval_log IS '检索记录(阶段 B;查询/范围/通道状态/结果/耗时)';
