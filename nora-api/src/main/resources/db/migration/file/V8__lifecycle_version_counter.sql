-- 生命周期版本持久化(2026-09-22,知识库优化阶段 A,方案 §5.4)。
--
-- 问题:pending_rag_sync.version 在送达成功后被 DELETE 连带消失——
-- 「删除(v1)→恢复(v2)→删除(v3)」每步都从 1 重新计数,而 rag 侧只接受
-- 严格更大的版本,于是第 2、3 步的通知全部被拒绝(实测:applied_version
-- 停在 1,恢复/再删除从未生效)。
--
-- 方案:版本号存到**独立的每文件计数器表**,不随待处理行清理而消失;
-- pending_rag_sync 只做投递队列(它那行的 version 只是本次投递的快照)。
CREATE TABLE file_lifecycle_version (
    file_id       BIGINT PRIMARY KEY,
    next_version  BIGINT NOT NULL DEFAULT 1,
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE file_lifecycle_version IS '文件生命周期事件的单调版本计数(不随待处理通知清理;阶段 A)';
COMMENT ON COLUMN file_lifecycle_version.next_version IS '下一个要分配的版本号(单调递增)';
