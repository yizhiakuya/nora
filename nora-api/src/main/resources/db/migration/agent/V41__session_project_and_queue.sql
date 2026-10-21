ALTER TABLE chat_session ADD COLUMN project_root TEXT;
ALTER TABLE chat_session ADD COLUMN queue_paused BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE chat_input_queue (
    id UUID PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('message', 'compact')),
    payload JSONB NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'claimed', 'consumed', 'withdrawn')),
    run_id TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX chat_input_queue_pending ON chat_input_queue(session_id, created_at) WHERE state = 'pending';
