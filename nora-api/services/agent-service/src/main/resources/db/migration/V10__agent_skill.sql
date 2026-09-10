-- 指令型技能:用户/agent 维护的指令文本,启用后以「技能目录」形式注入系统提示,
-- agent 通过 manage_skill 工具读取全文并按需遵循。与 MCP(可执行工具)互补。
CREATE TABLE IF NOT EXISTS agent_skill (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,
    description  VARCHAR(500) NOT NULL DEFAULT '',
    instructions TEXT         NOT NULL DEFAULT '',
    category     VARCHAR(50)  NOT NULL DEFAULT '自定义',
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_skill_enabled ON agent_skill (enabled, updated_at DESC);
