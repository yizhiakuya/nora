-- Model providers (BYO-Key), per initiation doc section 4.1.
-- API keys stored masked only? No — the test endpoint needs the real key.
-- Phase 2 dev: plaintext column (api_key); encryption (Jasypt) is a later hardening step.

CREATE TABLE model_provider (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    protocol    VARCHAR(20) NOT NULL,
    endpoint    VARCHAR(500),
    api_key     TEXT,
    enabled     BOOLEAN DEFAULT true,
    models      TEXT[],
    status      VARCHAR(20) DEFAULT 'untested'
);
