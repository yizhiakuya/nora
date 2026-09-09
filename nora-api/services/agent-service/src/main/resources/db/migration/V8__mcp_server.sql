-- MCP server registry: remote tool servers the agent can mount.
-- Credentials live in headers JSON (values are masked on read; raw is
-- only used server-side when connecting).
CREATE TABLE IF NOT EXISTS mcp_server (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    url           VARCHAR(500) NOT NULL,
    transport     VARCHAR(20)  NOT NULL DEFAULT 'STREAMABLE',  -- STREAMABLE | SSE
    headers       TEXT,                                        -- JSON object: { "Authorization": "Bearer ..." }
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'untested',    -- connected | error | untested
    status_detail VARCHAR(500),
    tools_cache   TEXT,                                        -- JSON array snapshot of tools/list
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
