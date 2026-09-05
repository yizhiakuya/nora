-- One thought block groups reasoning + tool calls by ReAct round.
ALTER TABLE agent_step ADD COLUMN IF NOT EXISTS round_index INTEGER;
