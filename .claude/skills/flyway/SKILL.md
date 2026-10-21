---
description: 新建和检查 Nora 按领域保留的 Flyway 迁移
argument-hint: [agent|file|rag|datasource|env|automation|notification] [迁移描述]
allowed-tools: Bash, Read, Write, Glob, Grep
---

# Flyway 迁移

一个应用连接一个 PostgreSQL，按领域独立执行 Flyway。配置在 `nora-api/src/main/java/com/nora/common/config/DatabaseConfig.java`。

| 目录 | schema |
|---|---|
| agent | schema_agent |
| file | schema_file |
| rag | schema_rag |
| datasource | schema_datasource |
| env | schema_env |
| automation | schema_automation |
| notification | schema_notification |

1. 在 `nora-api/src/main/resources/db/migration/<领域>/` 找最大版本号，新建 `V<N+1>__<snake_case>.sql`。
2. 已执行迁移不得改内容、改编号或合并；每个 schema 保留自己的 `flyway_schema_history`。迁移不解析 `${...}` 占位符，正文可存 MCP 环境变量引用。
3. 只做向前迁移，说明必要的锁表或数据变更风险。全新外部数据库先执行 `nora-api/docker/postgres-init.sql`，确保 public.vector 可用；Compose 会自动初始化新卷。
4. 在隔离数据库验证新装与升级。重启整个后端后检查各自 schema 的 history 和受影响业务：

```bash
docker exec nora-postgres psql -U nora -d nora -c "SELECT version, description, success FROM schema_agent.flyway_schema_history ORDER BY installed_rank DESC LIMIT 3"
```

替换为实际领域 schema、数据库和用户；不要输出密码。构建和重启参见 `/restart-service`。
