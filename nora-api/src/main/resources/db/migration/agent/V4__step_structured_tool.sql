-- Structured tool steps (harness research 2026-09): a tool step carries its
-- parsed input and typed result next to the legacy flat detail string, so the
-- timeline can render arguments/output without parsing detail. Terminal
-- status gains 'declined' (guardrail refusal / approval denial), distinct
-- from 'failed' (execution error).

ALTER TABLE agent_step ADD COLUMN IF NOT EXISTS tool_name VARCHAR(64);
ALTER TABLE agent_step ADD COLUMN IF NOT EXISTS tool_input JSONB;
ALTER TABLE agent_step ADD COLUMN IF NOT EXISTS tool_result JSONB;
