# Nora API

Nora 个人工作台 Java 后端。设计文档见 [docs/](docs/)：

- [docs/architecture-v2.md](docs/architecture-v2.md) — **微服务架构设计（当前基准，含 Phase 0–4 路线图）**
- [docs/agent-implementation-spec.md](docs/agent-implementation-spec.md) — **Agent 实施规格**（ReAct 协议、工具契约、高风险审批协议）
- [docs/agent-design-research.md](docs/agent-design-research.md) — Agent 设计调研（ReAct/Reflexion/ReWOO/Voyager/SWE-agent + 工业实践）
- [docs/harness-tool-calling-research-2026-09-05.md](docs/harness-tool-calling-research-2026-09-05.md) — 工具调用 Harness 调研
- [docs/frontend-impact-analysis.md](docs/frontend-impact-analysis.md) — 前端影响分析（零改动/需改/需新增三分类）
- [docs/nora-api-initiation-2026-09-04.md](docs/nora-api-initiation-2026-09-04.md) — 领域需求反推 + API 契约（单体版，保留作参考）

## 定位

- 微服务架构：7 个服务（gateway / file / rag / **agent** / datasource / env / automation）
- Spring Boot 3.3 · Java 21 · Spring Cloud Alibaba（Nacos + Dubbo + Sentinel）
- **LangChain4j**（Agent 循环 / AiServices / Tools / RAG / ChatMemory）
- PostgreSQL 16 + pgvector · Redis · RocketMQ（设计中）
- Maven 多模块（16 个）+ Docker Compose

## 当前状态（2026-09-06）

| 阶段 | 状态 | 说明 |
|------|------|------|
| Phase 0 基础设施 | ✅ 已完成 | 16 个 Maven 模块、docker-compose dev-basic（PG+pgvector / Redis / Nacos）、gateway 6 条路由 |
| Phase 1 RAG 链路 | ✅ 已完成 | file-service（Tika 解析）+ rag-service（分块 / Jina v3 1024 维 / pgvector HNSW） |
| Phase 2 Agent 链路 | 🔄 收尾中 | SSE ReAct 循环、工具调用、会话持久化、模型 Provider 管理已完成；**高风险审批流已开发未提交** |
| Phase 3 数据源 + 任务 | 🟡 部分完成 | datasource-service、automation-service 已上线；**通知 SSE（3.5）未实现** |
| Phase 4 环境控制台 | 🟡 部分完成 | env-service 容器管理与日志 SSE 已完成；**诊断 → 自动修复任务（4.4）未打通** |

整体进度评估见仓库根目录 [PROGRESS-REVIEW-2026-09-06.md](../PROGRESS-REVIEW-2026-09-06.md)。

### 服务与端点

| 服务 | 端口 | 主要端点 |
|------|------|---------|
| gateway-service | 8080 | 路由 `/api/{chat,files,rag,datasources,automations,environment}/**` |
| file-service | 8081 | `POST /files/upload`、`GET /files`、`DELETE /files`、`GET /files/{id}/preview`、`POST /files/{id}/index`、`POST /files/{id}/indexed` |
| rag-service | 8082 | `POST /rag/index`、`GET /rag/docs`、`GET /rag/index/stats`、`POST /rag/search`、`POST /rag/citations` |
| agent-service | — | `POST /chat`（SSE）、`POST /chat/approvals/{token}`、`GET /chat/sessions`、`GET /chat/sessions/{id}/messages`、`GET /chat/sessions/{id}/approvals`、`DELETE /chat/sessions/{id}`、`/models` CRUD + `/{id}/test` |
| datasource-service | — | 连接 CRUD、`POST /{id}/test`、`GET /{id}/schema`、`POST /{id}/query`、`POST /{id}/execute`、`GET /{id}/history` |
| env-service | — | `GET /services`、start / stop / restart、`GET /logs` |
| automation-service | — | 规则 CRUD、`POST /{id}/toggle`、`POST /{id}/run`、`GET /executions` |

## 快速开始

```bash
# 基础设施（PG :5432 / Redis :6379 / Nacos :8848）
cd nora-api && docker compose --profile dev-basic up -d

# 构建（16 模块全部构建 + 测试）
mvn install

# 起服务（示例：file + rag + agent + gateway）
java -jar services/file-service/target/file-service-0.1.0-SNAPSHOT.jar
java -jar services/rag-service/target/rag-service-0.1.0-SNAPSHOT.jar
java -jar services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar
java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar
# 验证：curl http://localhost:8080/api/chat/health → ok（经 Nacos 服务发现转发）
```

Embedding 使用 Jina AI OpenAI 兼容端点，密钥放在 `nora-api/.env.local` 的 `NORA_EMBEDDING_API_KEY`
（**禁止提交、禁止回显**）。未配置时检索类端点降级为业务异常，文档列表仍可用。

> ⚠️ **已知环境问题（2026-09-06）**：本机 `mvn` 启动失败
> （`ClassNotFoundException: org.codehaus.plexus.classworlds.launcher`，`M2_HOME` 未设置，
> Maven 位于 `D:/tools/apache-maven-3.9.16`）。修复前无法执行构建与测试，
> Java 改动请确保在可用环境中编译验证后再提交。

## 已知设计偏差

- **RocketMQ 未接入**：file → rag 索引触发当前为同步 REST（`@Async` 火忘调用），
  事件总线（outbox + MQ）推迟到后续阶段，代码中已注明。
- **Embedding 维度 1024**：v1 文档写 1536（OpenAI），实际使用 `jina-embeddings-v3` 上限 1024，迁移脚本中已注明。
- **Dubbo 未在 Phase 1/2 启用**：跨服务调用走 REST + Nacos 服务发现，Dubbo 随后续阶段引入。

## 前端对接

与 `nora-web` 完全独立，各自安装 / 启动。前端通过 `VITE_USE_BACKEND=true` 接入，
开发时 Vite 将 `/api` 代理到 gateway :8080。API 契约以 v1 文档第 5、6 章 + v2 文档第 8 章为准，
前端接入现状见 `nora-web/AGENTS.md` 的「后端接入现状」表。
