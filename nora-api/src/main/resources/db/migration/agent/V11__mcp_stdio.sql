-- stdio transport support for MCP servers: a local command is spawned as a
-- child process (stdin/stdout JSON-RPC). command = executable (npx.cmd /
-- node / docker ...), args = JSON array of argv, env = JSON object of extra
-- environment variables (sensitive: values masked on read, raw only used
-- when spawning). url becomes optional (stdio servers have no endpoint).
ALTER TABLE mcp_server ALTER COLUMN url DROP NOT NULL;
ALTER TABLE mcp_server ADD COLUMN IF NOT EXISTS command TEXT;
ALTER TABLE mcp_server ADD COLUMN IF NOT EXISTS args    TEXT;  -- JSON array: ["-y", "@scope/server"]
ALTER TABLE mcp_server ADD COLUMN IF NOT EXISTS env     TEXT;  -- JSON object: {"API_KEY": "..."}
