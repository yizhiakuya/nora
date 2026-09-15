-- 整轮耗时随消息落库（2026-09-15 用户反馈：「怎么没有一次任务的总计时了」）。
--
-- 背景：轮次总耗时此前只随 SSE done 事件下发给前端，从未落库。流式期间
-- 有计时，但刷新页面 / 切走再切回时前端从 chat_message 重建消息，拿不到
-- turnMetrics，「查看工作过程 · N 次工具调用 · Xs」就只剩前半截。
--
-- 只存总耗时（duration_ms）——它正是折叠行展示的那个数字。tokens / 首字
-- 延迟仍按轮次从 done 事件取（刷新后丢失可接受，且这两项另有 usage 表口径）。
--
-- 存量行保持 NULL：没有可信数据可回填，前端渲染时跳过（不显示错数字）。

ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

COMMENT ON COLUMN chat_message.duration_ms IS '整轮耗时(ms,assistant 消息):done 事件同源;NULL=旧数据未记录';
