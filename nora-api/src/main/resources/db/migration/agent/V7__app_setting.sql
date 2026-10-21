-- Generic app-level settings KV (JSONB values), for runtime-mutable config
-- that must be editable from the settings UI without a process restart.
-- First consumer: outbound proxy (nora.proxy.* was env-only before).

CREATE TABLE IF NOT EXISTS app_setting (
    key        VARCHAR(100) PRIMARY KEY,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
