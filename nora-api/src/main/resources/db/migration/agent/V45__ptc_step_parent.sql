ALTER TABLE agent_step ADD COLUMN parent_step_id VARCHAR(255);
ALTER TABLE agent_step ADD COLUMN call_index INTEGER;
