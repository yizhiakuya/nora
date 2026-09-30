# Nora API

Nora(个人 AI 助手)的 Java 后端。设计文档见 [docs/](docs/)：

- [docs/architecture-v2.md](docs/architecture-v2.md) — **微服务架构设计（当前基准，含 Phase 0–4 路线图）**
- [docs/agent-implementation-spec.md](docs/agent-implementation-spec.md) — **Agent 实施规格**（ReAct 协议、工具契约、高风险审批协议）
- [docs/agent-permission-and-tools-design.md](docs/agent-permission-and-tools-design.md) — **权限/风险/工具权威参考**（改 RiskClassifier/toolsSpec/审批前必读）
- [docs/harness-tool-calling-research-2026-09-05.md](docs/harness-tool-calling-research-2026-09-05.md) — 工具调用 Harness 调研

## 定位

- 微服务架构：**8 个服务**（gateway / file / rag / agent / datasource / env / automation / **notification**）
- Spring Boot 3.3 · Java 21 · Spring Cloud Alibaba（Nacos 服务发现）
- PostgreSQL 16 + pgvector · Redis（平台组件：嵌入缓存 / 审批票据）· **Kafka**（通知事件总线）
- Maven 多模块 + Docker Compose（PG+pgvector / Redis / Nacos / Kafka）

## 当前状态（2026-09-20）

系统是**能实际使用的单用户工作台**。最新全面分析见仓库根目录
[PROJECT-ANALYSIS-2026-09-19.md](../PROJECT-ANALYSIS-2026-09-19.md)（其 P1/P2 问题已在 09-19/20 修复）。

| 阶段 | 状态 | 说明 |
|------|------|------|
| Phase 0 基础设施 | ✅ | Maven 多模块、docker-compose dev-basic（PG+pgvector / Redis / Nacos / Kafka）、gateway 路由 |
| Phase 1 RAG 链路 | ✅ | file-service（Tika 解析）+ rag-service（分块 / Jina v3 1024 维 / pgvector HNSW + pg_trgm 混合检索） |
| Phase 2 Agent 链路 | ✅ | SSE 流式、工具循环、会话持久化、模型 Provider、审批流、断线重连、上下文压缩 |
| Phase 3 数据源 + 任务 | ✅ | datasource-service（PG/MySQL/Redis）、automation-service（手动/每日/每周） |
| Phase 4 环境控制台 | ✅ | env-service 容器管理 / 日志 / 进程守护 / AI 诊断→修复任务 |
| 通知 | ✅ | **Kafka 事件总线**（notification-service 消费落库，前端轮询；非文档计划的 SSE） |
| 访问控制 | ✅ | **令牌登录**（`NORA_AUTH_TOKEN`，缺省免登录；网关一处生效） |

**已知能力边界（与产品文案对齐）**：自动任务的「文件上传/服务异常」触发条件**未接通**（UI 已标注不可选，后端调度只扫 daily/weekly）；当前系统按**单用户、单 Agent 实例**运行（LiveTurn/activeTurns 为进程内状态）。

### 服务与端点

| 服务 | 端口 | 主要端点 |
|------|------|---------|
| gateway-service | 8080 | 路由 `/api/{chat,files,rag,datasources,automations,environment,notifications,media,mcp,skills,workspace,log}/**`；令牌鉴权 |
| file-service | 8081 | 文件/文件夹 CRUD、`POST /upload`、`GET /{id}/preview|raw`、`GET /download`（zip）、`POST /{id}/index`；**RAG 生命周期通知带持久化重试**（`pending_rag_sync`） |
| rag-service | 8082 | `POST /rag/index[/text]`、docs CRUD/reindex、`POST /rag/search`、`POST /rag/citations`、`POST /rag/docs/by-file/{id}`（文件生命周期联动） |
| agent-service | 8083 | `POST /chat/sessions/{id}/messages`（SSE）、审批、会话/消息、`/turn/live|stream`（断线重连）、`/models`、MCP 管理、技能、工作区、媒体缓存 |
| datasource-service | 8084 | 连接 CRUD、test、schema、`POST /{id}/query`（**数据库级只读连接**）、`POST /{id}/execute`（受控写）、history；引擎 postgresql / mysql / redis |
| env-service | 8085 | 容器/进程/日志纳管、`GET /services`、start/stop/restart、日志流、守护事件 |
| automation-service | 8086 | 规则 CRUD、`POST /{id}/toggle|run`、`GET /executions` |
| notification-service | 8087 | `GET /api/notifications`、unread-count、read-all、清空（Kafka 消费落库） |

## 快速开始

```bash
# 基础设施（PG :5432 / Redis :6379 / Nacos :8848）
cd nora-api && docker compose --profile dev-basic up -d

# 构建（14 模块全部构建 + 测试）
mvn install

# 起服务（示例：file + rag + agent + gateway）
java -jar services/file-service/target/file-service-0.1.0-SNAPSHOT.jar
java -jar services/rag-service/target/rag-service-0.1.0-SNAPSHOT.jar
java -jar services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar
java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar
# 验证：curl http://localhost:8080/api/chat/health → ok（经 Nacos 服务发现转发）
```

Embedding 使用 Jina AI OpenAI 兼容端点，密钥放在 `nora-api/.env.local` 的 `NORA_EMBEDDING_API_KEY`
（**禁止提交、禁止回显**）。启动时由 nora-common 的 `DotenvEnvironmentPostProcessor` 自动加载
（最低优先级，真实环境变量优先；查找顺序 `NORA_ENV_FILE` > cwd/.env.local > cwd/nora-api/.env.local）。
未配置时检索类端点降级为业务异常，文档列表仍可用。外网调用（Jina/LLM 上游）需经代理，`.env.local`
中配 `NORA_PROXY_ENABLED/HOST/PORT`。

> ✅ **环境说明（2026-09-06 已修复）**：本机 Git Bash 下 `mvn` 曾启动失败
> （`ClassNotFoundException: plexus.classworlds.launcher`）。根因是 Maven 官方 `bin/mvn`
> 脚本不识别 Git Bash 的 `MINGW64_NT` uname、未把路径转回 Windows 格式；
> 已在 `D:/tools/apache-maven-3.9.16/bin/mvn` 中补 `MSYS*)` 分支并让 `MINGW*` 启用
> cygpath 转换（原脚本备份为 `mvn.bak-20260906`）。**升级 Maven 后需重新打此补丁。**
> 当前 `mvn test` 全绿：14 模块 SUCCESS，272 测试 0 失败（2026-09-27）。

## 已知设计偏差

- **Dubbo/Sentinel 未接线**（2026-09-19 审计确认）：跨服务调用一律 RestClient + envelope + Nacos 服务发现；
  POM 中的 Dubbo/Sentinel 版本声明不代表已投入使用。
- **Embedding 维度 1024**：v1 文档写 1536（OpenAI），实际使用 `jina-embeddings-v3` 上限 1024，迁移脚本中已注明。
- **检索为混合检索（向量 + pg_trgm）**：`V3__hybrid_search.sql` 引入 `pg_trgm` 扩展，
  关键词侧用 `strict_word_similarity`（免中文分词），与向量侧按权重 RRF 融合
  （`nora.retrieval.*`）。`pg_trgm` 缺失时自动降级为纯向量，不影响服务启动。
  相似度下限 `min-score` 默认 `0.51`（2026-09-22 评测校准：无关查询最高 0.476、
  相关查询最低 0.540，取两带中点；复校详见 `scripts/rag-eval.py` 基线）；换 embedding
  模型后需重新标定，否则可能全砍或全放。
- **文件→RAG 同步**：生命周期通知（删/恢复/永久删）带持久化重试（`pending_rag_sync` + 定时退避，2026-09-20）；
  索引触发仍为 `@Async` 火忘调用（失败可在文件页重新索引）。
- **单实例假设**：LiveTurn / activeTurns / 自动任务防重集合都在进程内；Redis 审批票据不等于多实例恢复能力。

## 前端对接

与 `nora-web` 完全独立，各自安装 / 启动。前端通过 `VITE_USE_BACKEND=true` 接入，
开发时 Vite 将 `/api` 代理到 gateway :8080。API 契约以 v1 文档第 5、6 章 + v2 文档第 8 章为准，
前端接入现状见 `nora-web/AGENTS.md` 的「后端接入现状」表。
