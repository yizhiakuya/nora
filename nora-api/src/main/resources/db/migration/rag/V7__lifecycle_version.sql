-- 文件生命周期通知的版本记录(R02,2026-09-20)。
--
-- 背景:file-service 按「每文件最新目标状态 + 单调版本」投递生命周期通知
-- (软删/恢复/永久删除)。HTTP 无顺序保证——「删除(v2)→恢复(v3)→删除(v4)」
-- 时,在途的旧请求(如 v2 的删除、v3 的恢复)可能晚到,若无条件执行会把
-- RAG 状态改成与文件当前状态不一致的旧值。
--
-- 方案:rag 侧记录每个文件已应用的**最大版本**;收到的请求版本 ≤ 已应用
-- 版本时直接忽略(不执行、返回 0);版本更大才执行并推进记录。
-- 表极小(每文件一行),随文件删除可清理。
CREATE TABLE rag_lifecycle_version (
    file_id        BIGINT PRIMARY KEY,
    applied_version BIGINT NOT NULL,
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE rag_lifecycle_version IS '文件生命周期通知已应用的最大版本(R02 乱序防护)';
