# Nora API

运行在宿主系统上的单用户智能体，Java 21 / Spring Boot 3.3 / LangChain4j。
后端是一个 Maven 应用、一个 JVM、一个 HTTP 端口（默认 18080）。

## 职责

`src/main/java/com/nora/` 按 agent、file、rag、datasource、env、automation、notification 分包。
HTTP 控制器只接入请求；Agent 工具与控制器共用领域服务，不再请求自己的 HTTP 接口。

- 会话、排队、插入、压缩、取消和自动任务使用 `ChatRunService`；`ChatTurnRunner` 执行并通过 `ChatRunFinalizer` 保存终态。SSE 是订阅方式。
- Agent 编排、工具执行、上下文和模型组件由 Spring 构造器统一装配；内置工具固定注册，MCP 工具动态发现。
- 全部内置工具从实际 `@Tool` 方法、`@P` 或请求 record 生成契约，由 LangChain4j `DefaultToolExecutor` 绑定参数和执行；Nora 统一管理审批、并发、取消与记录。MCP 工具保留远端 schema。
- 模型循环直接使用 SDK 的消息、工具声明和调用类型；JSON 仅用于存储、HTTP 与前端事件，不重复转换模型历史。
- 取消停止模型和工具执行，终态提交暂时清除线程中断并在保存后恢复；回答、工作过程和 Run 状态保存成功后才发出 `done`。
- 文件删除、恢复、清理与知识库状态共用数据库事务；磁盘清理在提交后。启动时处理旧 `pending_rag_sync` 未送达记录。
- 通知直接写 PostgreSQL，写入失败记录日志，不影响主业务。
- 嵌入使用 Caffeine 有界内存缓存（32 MiB 权重上限、7 天过期）；审批票据绑定会话并在 120 秒后拒绝。
- SQL / Redis 命令支持真实读写；自动 SQL 规则必须显式绑定数据源，不猜测第一个连接。
- 自动任务没有用户在线审批：FULL 可执行 LOW/HIGH，CRITICAL 拒绝；环境诊断使用 ASSIST，只执行 LOW。

## 数据与迁移

仅依赖 PostgreSQL 16 + pgvector。既有七个 `schema_*` 和各自 Flyway 历史保留，迁移位于 `src/main/resources/db/migration/{domain}`。
`DatabaseConfig` 逐域执行迁移，应用连接的 search_path 包含所有领域和 public。旧迁移内容与校验和保持不变。

新数据库须先执行 `docker/postgres-init.sql` 安装 vector；Compose 首次创建数据卷时自动执行。
Redis 继续作为可连接的用户数据源，平台不再需要 Redis、Nacos 或 Kafka。
系统按单后端实例运行，内存中的 Run 占用、审批和浏览器所有权不支持多个副本同时运行。

## 开发与检查

```bash
cd nora-api
docker compose up -d
mvn verify
java -jar target/nora-api-0.1.0-SNAPSHOT.jar
```

仓库根 `./nora.sh build` 会停止本项目后端、执行 verify、启动并检查健康；Windows 由 `scripts/backend.ps1` 执行。
模型在 Agent → 模型中配置并保存至数据库；`.env.local` 自动读取，真实环境变量优先。

常用环境变量：`NORA_PORT`、`NORA_DB_URL`、`NORA_DB_USERNAME`、`NORA_DB_PASSWORD`、`NORA_AUTH_TOKEN`、`NORA_FILE_STORAGE_DIR`、`NORA_AGENT_WORKSPACE`、`LOG_PATH`、`NORA_EMBEDDING_API_KEY`、`NORA_PROXY_*`。
未设置访问令牌时免登录；配置后所有业务接口在同一 servlet 过滤器鉴权，支持 Bearer 和媒体 URL 的 token 参数。

## 外部接口

原有 `/api/chat`、`/files`、`/rag`、`/datasources`、`/environment`、`/automations`、`/notifications`、`/models`、`/mcp`、`/skills`、`/workspace`、`/media` 路由保留。
`/api/chat/agent/run`、RAG 的内部文件生命周期回调和文件已索引回调已删除，内部调用直接使用领域方法。
健康检查：`GET /actuator/health`；访问状态：`GET /api/auth/status`。

## 文档

- [部署与升级](../docs/deployment.md)
- [精简方案与验证](../docs/dev/backend-simplification-2026-10-11.md)
- [权限、风险与工具](docs/agent-permission-and-tools-design.md)
- [早期架构设计（历史档案）](docs/architecture-v2.md)
