-- 会话标题来源标记：区分「首轮占位标题」与「AI 生成的标题」。
--
-- 背景：标题改为「先用首条消息开头若干字占位 → 异步让 AI 起简短标题覆盖」。
-- 前端需要知道当前标题是否还是占位（AI 尚未返回），才能决定要不要轮询兜底
-- 拉取——靠文本形态猜（比如有没有省略号）不可靠：短消息的占位标题也可能
-- 恰好没有省略号，而 AI 标题也可能被截断加省略号。
--
-- DEFAULT FALSE：存量会话（旧的「原文当标题」）一律视为未生成，下次进对话页
-- 若仍停留在这类标题上会被兜底逻辑刷新为 AI 标题，符合预期。
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS title_generated BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN chat_session.title_generated IS '标题是否已由 AI 生成(TRUE)/ 仍是首轮占位(FALSE)';
