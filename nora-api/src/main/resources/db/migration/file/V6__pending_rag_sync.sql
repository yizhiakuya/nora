-- 文件→RAG 生命周期通知的待处理队列(2026-09-20)。
--
-- 为什么需要:删除/恢复/永久删除文件时对 rag-service 的通知是发后即忘
-- (@Async HTTP),失败只记日志——「文档早已索引,删除时 RAG 不可用,随后
-- RAG 恢复」的场景下旧文档会永远留在检索里(审查报告 P2)。这里把每次
-- 通知持久化,后台定时重试直到成功(或文件生命周期再次变化被新通知覆盖)。
--
-- 设计:
--   - (file_id, mode) 唯一:同一文件同一动作只需送达一次;文件被反复
--     删除/恢复时,新通知覆盖旧行(内容相同,幂等);
--   - attempts/last_error 供排障;next_attempt_at 做指数退避;
--   - 成功即删行——表只保留"未送达"的通知,规模恒定在故障窗口内。
CREATE TABLE pending_rag_sync (
    id              BIGSERIAL PRIMARY KEY,
    file_id         BIGINT NOT NULL,
    mode            VARCHAR(16) NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    next_attempt_at TIMESTAMP NOT NULL DEFAULT now(),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_pending_rag_sync UNIQUE (file_id, mode)
);

COMMENT ON TABLE pending_rag_sync IS '文件→RAG 生命周期通知的待处理队列(后台重试,送达即删)';
COMMENT ON COLUMN pending_rag_sync.mode IS 'soft(删除)/restore(恢复)/purge(永久删除)';
