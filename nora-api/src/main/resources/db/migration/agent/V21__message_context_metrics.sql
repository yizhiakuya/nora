-- 上下文计量随消息落库(2026-09-19 用户反馈「上下文不准」)。
--
-- 背景:done 事件下发的 promptTokens(最后一轮请求的完整 prompt 估算,含
-- 系统提示+全部历史)与 contextWindow(生效窗口)此前只活在 SSE 事件里——
-- 刷新/切会话后前端从历史重建消息拿不到,上下文指示器回退到「字符数÷4」
-- 估算:中文 1 字≈1 token,÷4 严重低估(实测真实 7.6k 显示成 1k)。
--
-- 与 V16 的 duration_ms 同款处理:assistant 消息存当轮请求的 prompt 估算
-- 与生效窗口;NULL=旧数据(前端跳过,退回字符估算不显示错数字)。

ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS prompt_tokens INT;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS context_window BIGINT;

COMMENT ON COLUMN chat_message.prompt_tokens IS '当轮请求的完整 prompt token 估算(done 事件同源):assistant 消息;NULL=旧数据';
COMMENT ON COLUMN chat_message.context_window IS '当轮生效的上下文窗口(模型配置):assistant 消息;NULL=旧数据';
