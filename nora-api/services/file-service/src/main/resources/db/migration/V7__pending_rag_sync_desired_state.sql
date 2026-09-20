-- 生命周期通知的最终状态一致性(2026-09-20,R02)。
--
-- 问题:原表按 (file_id, mode) 唯一,「删除→恢复→删除」会留下 soft 与
-- restore 两行,重试顺序/在途旧请求可能让最终 RAG 状态与文件状态不一致
-- (例如文件已删,最后送达的却是 restore)。
--
-- 方案(每文件最新目标状态 + 单调版本):
--   - 每文件只保留一行(unique file_id),列 desired_state = 最新目标状态;
--   - version 每次文件生命周期变化 +1,接收端按版本条件确认(旧版本请求
--     晚到不覆盖新状态);
--   - 送达条件:通知携带的 version 等于当前行版本(说明没有更新的状态
--     在等待),且 desired_state 与文件当前状态一致。
--
-- 迁移:旧表结构数据一次性转换为新语义(行数极少,只保留每文件最新一条)。

-- 1) 新列(可空,兼容旧数据先发布)
ALTER TABLE pending_rag_sync ADD COLUMN IF NOT EXISTS desired_state VARCHAR(16);
ALTER TABLE pending_rag_sync ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1;

-- 2) 旧数据转换:每文件保留最新一条(id 最大),把其 mode 映射为 desired_state
--    mode → desired_state:soft→deleted, restore→present, purge→purged
UPDATE pending_rag_sync SET desired_state = CASE mode
    WHEN 'soft' THEN 'deleted'
    WHEN 'restore' THEN 'present'
    WHEN 'purge' THEN 'purged'
    ELSE mode END
WHERE desired_state IS NULL;

-- 3) 同文件旧行清理(只留最新一条),然后改为按 file_id 唯一
DELETE FROM pending_rag_sync a USING pending_rag_sync b
WHERE a.file_id = b.file_id AND a.id < b.id;

ALTER TABLE pending_rag_sync DROP CONSTRAINT IF EXISTS uq_pending_rag_sync;
ALTER TABLE pending_rag_sync ADD CONSTRAINT uq_pending_rag_sync UNIQUE (file_id);

-- 4) mode 列保留为"最后写入的原始动作"(排障用);desired_state 是权威目标
COMMENT ON COLUMN pending_rag_sync.desired_state IS '每文件最新目标状态:present/deleted/purged(R02)';
COMMENT ON COLUMN pending_rag_sync.version IS '单调版本:每次生命周期变化 +1;送达按版本条件确认';
