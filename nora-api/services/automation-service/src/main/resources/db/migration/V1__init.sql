-- automation-service schema (schema_automation), per initiation doc section 4.1.
-- action: JSONB {"type": "sql", "sql": "...", "connectionId": 1} (v1 executor: SQL only)

CREATE TABLE automation_rule (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,      -- manual / daily / weekly / file / error
    trigger_expr VARCHAR(500),              -- human-readable label (cron derived from type)
    action       JSONB NOT NULL,
    enabled      BOOLEAN DEFAULT true,
    status       VARCHAR(20) DEFAULT 'active',
    last_run_at  TIMESTAMP,
    created_at   TIMESTAMP DEFAULT now()
);

CREATE TABLE execution_record (
    id          BIGSERIAL PRIMARY KEY,
    rule_id     BIGINT REFERENCES schema_automation.automation_rule (id) ON DELETE CASCADE,
    duration_ms BIGINT,
    status      VARCHAR(20),                -- success / failed
    detail      TEXT,
    started_at  TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_exec_rule ON execution_record (rule_id, started_at DESC);
