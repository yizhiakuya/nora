-- datasource-service schema (schema_datasource), per initiation doc section 4.1.
-- Phase 3 dev: password plaintext column; encryption (Jasypt) is a later step.

CREATE TABLE db_connection (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    engine       VARCHAR(20) NOT NULL,
    host         VARCHAR(255),
    port         INTEGER,
    database     VARCHAR(100),
    username     VARCHAR(100),
    password     TEXT,
    status       VARCHAR(20) DEFAULT 'untested'
);

CREATE TABLE query_history (
    id             BIGSERIAL PRIMARY KEY,
    connection_id  BIGINT REFERENCES schema_datasource.db_connection (id) ON DELETE CASCADE,
    sql_text       TEXT NOT NULL,
    duration_ms    BIGINT,
    rows_affected  INTEGER,
    status         VARCHAR(20),
    executed_at    TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_query_history_conn ON query_history (connection_id, executed_at DESC);
