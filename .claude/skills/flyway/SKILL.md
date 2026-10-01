---
description: 新建/检查 Nora 各服务的 Flyway 迁移(命名、位置、校验)
argument-hint: [服务名] [迁移描述,如 add-index-on-messages]
allowed-tools: Bash, Read, Write, Glob, Grep
---

# Flyway 迁移约定

服务: $ARGUMENTS(缺省 agent);描述: 第二个参数。库均为 `nora`(nora-postgres,5432,用户/密码 nora)。

## 1. 找最新版本号

```bash
ls nora-api/services/<服务名>-service/src/main/resources/db/migration/
```

取最大 `V<N>__` 的 N,新迁移 = N+1。

## 2. 命名规则(必须遵守)

- 位置: `services/<服务名>-service/src/main/resources/db/migration/`
- 文件: `V<下一个版本>__<snake_case 描述>.sql`(双下划线分隔版本与描述)
- 每服务独立 schema: agent→schema_agent, rag→schema_rag(以该服务 application.yml 的 currentSchema 为准),SQL 里写表名即可,勿跨 schema 引用

## 3. 写迁移

- 只向前迁移,不写 DOWN
- ALTER 有锁风险时加 `-- 锁表风险:短` 注释说明;大表加列用 `ADD COLUMN ... DEFAULT` 前先评估
- 在 nora-postgres 运行时,可用只读方式先查现状: `docker exec nora-postgres psql -U nora -d nora -c "\dt <schema>.*"`

## 4. 生效

重启对应服务(用 /restart-service <服务名>)让 Flyway 自动执行,然后验证:

```bash
docker exec nora-postgres psql -U nora -d nora -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 3;"
```

汇报: 新文件路径、版本号、重启后 flyway_schema_history 最新记录。
