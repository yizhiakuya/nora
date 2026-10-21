-- 统一数据库工具；只替换内置手册中的旧指引，保留其他正文和用户配置。
UPDATE agent_skill
SET instructions = replace(replace(replace(instructions,
        'execute_sql(只读 SQL)、execute_write_sql(写 SQL,需批准)',
        'execute_sql(SQL/Redis 读写与结构变更，按会话权限模式执行)'),
        '`execute_sql`(查询)/`execute_write_sql`(写入)',
        '`execute_sql`(SQL/Redis 查询、写入与结构变更)'),
        '只读=LOW(自动);写 SQL/容器启停',
        '只读工具=LOW(自动);数据库命令(execute_sql)/容器启停'),
    updated_at = now()
WHERE name = '工作台使用手册' AND deleted_at IS NULL
  AND instructions LIKE '%execute_write_sql%';
