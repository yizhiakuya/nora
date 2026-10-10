ALTER TABLE chat_run ADD COLUMN stop_reason VARCHAR(64);
ALTER TABLE chat_run ADD COLUMN error_message TEXT;
ALTER TABLE chat_run ADD COLUMN had_tool_errors BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_step ADD COLUMN run_id VARCHAR(64);
ALTER TABLE agent_step ADD COLUMN step_id VARCHAR(255);
CREATE INDEX idx_agent_step_run_id ON agent_step (run_id, step_id);
