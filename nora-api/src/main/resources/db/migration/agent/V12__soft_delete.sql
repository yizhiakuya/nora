-- 软删除:所有业务实体改为 UPDATE 标记删除,不做物理 DELETE(2026-09-12 用户要求)。
-- deleted_at IS NULL = 存活;有值 = 删除时间(一列顶「是否删+何时删」,对齐
-- Laravel softDeletes / GORM DeletedAt 惯例)。
--
-- 涉及:chat_session / chat_message / agent_skill / mcp_server / model_provider。
-- agent_step / agent_reflection 无删除入口(审计数据),不加列。
--
-- 唯一名释放:软删后名字不再占用,可以重建同名实体——UNIQUE(name) 约束
-- 改为 partial unique index(WHERE deleted_at IS NULL)。

-- ---------- chat_session / chat_message ----------
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- ---------- agent_skill ----------
ALTER TABLE agent_skill ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE agent_skill DROP CONSTRAINT IF EXISTS agent_skill_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS agent_skill_name_active ON agent_skill (name) WHERE deleted_at IS NULL;
-- 目录查询走 (enabled, updated_at) 且要求存活:重建索引带上过滤条件
DROP INDEX IF EXISTS idx_agent_skill_enabled;
CREATE INDEX IF NOT EXISTS idx_agent_skill_enabled ON agent_skill (enabled, updated_at DESC) WHERE deleted_at IS NULL;

-- ---------- mcp_server ----------
ALTER TABLE mcp_server ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE mcp_server DROP CONSTRAINT IF EXISTS mcp_server_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS mcp_server_name_active ON mcp_server (name) WHERE deleted_at IS NULL;

-- ---------- model_provider ----------
ALTER TABLE model_provider ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
