ALTER TABLE chat_run ADD COLUMN kind VARCHAR(24) NOT NULL DEFAULT 'chat';

CREATE TABLE chat_context_checkpoint (
    session_id VARCHAR(64) PRIMARY KEY REFERENCES chat_session(id) ON DELETE CASCADE,
    checkpoint JSONB NOT NULL
);

COMMENT ON TABLE chat_context_checkpoint IS '最新成功的模型工作上下文；原始消息保留，编辑历史时失效';
