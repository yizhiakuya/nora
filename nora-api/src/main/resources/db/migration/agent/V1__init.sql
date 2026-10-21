-- agent-service schema (schema_agent), per architecture-v2.md sections 4.9 and 7.1.
-- Phase 2 minimal loop: sessions + messages with steps/sources persisted per message.

CREATE TABLE chat_session (
    id         VARCHAR(64) PRIMARY KEY,
    title      VARCHAR(255),
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE chat_message (
    id         VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES schema_agent.chat_session (id) ON DELETE CASCADE,
    role       VARCHAR(10) NOT NULL,
    content    TEXT,
    steps      TEXT,
    sources    TEXT,
    created_at TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_agent_message_session ON chat_message (session_id, created_at);
