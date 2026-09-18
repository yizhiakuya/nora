-- MCP 工具延迟加载(2026-09-18 工具设计分析 P2-9):
-- 服务器级 tool_policy:
--   eager(默认,现状) — tools_cache 里的工具全部挂载为 mcp__<server>__<tool>,
--                       每轮注入 tools spec(固定 token 成本);
--   lazy             — 不挂载为独立工具;agent 经 manage_mcp action=tools 查
--                       清单(缓存快照,不触发远端)、action=call 按名调用。
-- 动机:76 工具/≈9K tokens 固定成本,其中 github 44 工具 30 天只用 3 个。
-- 对齐 MCP Client Best Practices「渐进披露 + 单一稳定 call_tool 元工具」
-- (工具数组不随会话增删,不破坏 prompt 缓存)。
ALTER TABLE mcp_server ADD COLUMN IF NOT EXISTS tool_policy VARCHAR(10) NOT NULL DEFAULT 'eager';

COMMENT ON COLUMN mcp_server.tool_policy IS 'eager=工具直接挂载 / lazy=按需(manage_mcp action=tools|call)';
