-- PROC 源:环境变量(JSON object,如 {"JAVA_OPTS":"-Xmx512m"}),spawn 时注入子进程
ALTER TABLE managed_source ADD COLUMN env_vars JSONB;
