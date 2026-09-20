-- 规则 → 会话映射(2026-09-20,定时任务=往会话发消息)。
--
-- 每条规则固定一个 agent 会话(chat_session,跨 schema 无外键,用 id 关联);
-- 每次触发(手动运行/计划执行)往该会话追加一条 sender='automation' 的消息,
-- AI 回答、步骤、产物照常落进会话——用户在会话列表就能看到定时任务的完整记录。
--
-- 规则删除时保留会话与历史(方案 §7.3);会话被用户删除时,规则下次触发
-- 重新建会话(automation-service 侧按需 ensure)。
ALTER TABLE automation_rule ADD COLUMN IF NOT EXISTS session_id VARCHAR(64);

COMMENT ON COLUMN automation_rule.session_id IS '规则专属会话 id(chat_session;每次触发往该会话发消息)';
