-- 定时任务消息的发送者标记(2026-09-20)。
--
-- 语义(用户明确):定时任务 = 定时器往会话里发一条消息,让 AI 执行指令。
-- 因此:
--   - 每条规则有**自己的会话**(chat_session.origin='automation'),
--     出现在会话列表里,标题「定时任务:规则名」;
--   - 每次触发写入的消息 sender='automation'——前端据此在气泡上显示
--     「定时任务」徽章,而不是伪装成用户手输的消息。
--
-- 兼容:旧会话/旧消息 origin/sender 均为 NULL,按普通(user)处理。
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS origin VARCHAR(16) DEFAULT 'user';
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS sender VARCHAR(16) DEFAULT 'user';

-- 规则 → 会话映射:automation_rule.session_id(一条规则固定一个会话,多次触发
-- 追加消息;规则删除时保留会话与历史,方案 §7.3「删除保留历史」)
-- 该列在 automation schema,由 automation-service 的迁移维护,这里只注释约定。

COMMENT ON COLUMN chat_session.origin IS 'user=用户创建;automation=定时任务会话';
COMMENT ON COLUMN chat_message.sender IS 'user=用户发送;automation=定时任务发送;assistant=AI';

-- 定时任务会话的标题列:沿用 title 字段(「定时任务:规则名」);
-- title_generated 保持 false——它由 AI 起标题,定时会话不参与
