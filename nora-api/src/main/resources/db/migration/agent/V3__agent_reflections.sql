CREATE TABLE IF NOT EXISTS agent_reflection (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    task_signature VARCHAR(255) NOT NULL,
    reflection TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_reflection_session
    ON agent_reflection (session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_step (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    step_index INTEGER NOT NULL,
    step_type VARCHAR(20) NOT NULL,
    title VARCHAR(255),
    detail TEXT,
    status VARCHAR(20),
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_step_session ON agent_step (session_id, step_index);
