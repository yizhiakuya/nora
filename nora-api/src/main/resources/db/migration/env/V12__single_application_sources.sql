-- Retire only built-in log/container sources; custom sources remain unchanged.
UPDATE managed_source SET enabled = FALSE
WHERE deleted_at IS NULL AND (
    (kind = 'FILE' AND name IN ('gateway-service','agent-service','rag-service','datasource-service',
         'env-service','file-service','automation-service','notification-service')
         AND file_log_path = name || '-text.log')
    OR (kind = 'DOCKER' AND name IN ('nora-nacos','nora-redis') AND container_name = name)
);
INSERT INTO managed_source(kind, name, file_log_path)
VALUES ('FILE', 'nora', 'nora-text.log') ON CONFLICT(name) WHERE deleted_at IS NULL DO NOTHING;
