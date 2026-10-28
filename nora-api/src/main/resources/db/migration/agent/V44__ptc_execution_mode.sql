ALTER TABLE chat_session ADD COLUMN execution_mode varchar(16) NOT NULL DEFAULT 'standard'
    CHECK (execution_mode IN ('standard', 'ptc'));
ALTER TABLE chat_run ADD COLUMN execution_mode varchar(16) NOT NULL DEFAULT 'standard'
    CHECK (execution_mode IN ('standard', 'ptc'));
