ALTER TABLE chat_message ADD COLUMN run_id VARCHAR(64);
ALTER TABLE chat_message ADD COLUMN task_context JSONB;
CREATE INDEX idx_chat_message_run_id ON chat_message (run_id);
ALTER TABLE agent_step ADD COLUMN context JSONB;
