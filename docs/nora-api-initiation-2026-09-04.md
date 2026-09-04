# Nora API 立项设计文档 · Java 后端

> 状态：立项设计稿 · 目标：将 nora-web 纯前端 Mock 全面升级为前后端一体的个人工作台
> 日期：2026-09-04 · 前端基线：`18ffa9c` · 仓库：`D:\claude\Nora\nora-web`

---

## 1. 立项背景

nora-web 已完成 9 大页面的前端实现（首页/文件/对话/知识库/AI 能力/数据源/环境控制台/自动任务/设置中心），全部业务数据为 Mock，无后端支撑。前端已通过三层隔离设计为后端接入预留了清晰的契约边界：

- **API 契约层**：`src/lib/services/ragService.ts` 集中定义 3 个 RAG 端点
- **状态层**：zustand + persist stores，字段结构与后端实体一一对应
- **类型层**：`src/types/index.ts` 全局定义所有业务实体

后端立项的目标：**实现所有前端已"预约"的 API，且前端调用方零改动**。

---

## 2. 前端契约反推清单

通过分析前端代码，以下是完整 API 需求映射：

### 2.1 RAG 管线（`ragService.ts` 明确预留）

| 前端类型 | Java 对应实体 | 端点 | 方法 |
|---------|--------------|------|------|
| `KnowledgeDoc` | `KnowledgeDocument` | `/api/rag/docs` | CRUD |
| `IndexStats` | `IndexStatistics` | `/api/rag/index/stats` | GET |
| `RetrievalResult` | `RetrievalResult` | `/api/rag/search` | POST |
| `Citation` | `Citation` | `/api/rag/citations` | POST |
| `KnowledgeSource` | enum | — | — |

### 2.2 文件管理（`useFiles` + 文件预览器）

| 前端行为 | 后端需求 |
|---------|---------|
| `addFile(name)` | 上传 → 存储 → 元数据入库 |
| `deleteFiles(ids)` | 物理删除 + 级联删索引 |
| `markIndexed(id)` | 触发异步索引 → 状态回推 |
| 文件预览（pdf/word/excel/image/text） | 服务端解析 → 结构化数据 |

### 2.3 对话（`ChatMessage` 含 steps + sources + 流式）

| 字段 | 含义 |
|------|------|
| `steps[].type: think/tool` | 思维链 + 工具调用（需逐步推送） |
| `sources[]` | RAG 引用来源 |
| `isTyping` | 流式标志 → 必须 SSE |
| `ModelProvider.protocol` | openai/ollama/anthropic 三协议适配 |

### 2.4 数据源（`DbConnection` + `DbTable` + `QueryHistory`）

- JDBC 连接管理（postgresql/mysql/sqlite），Redis 用 Lettuce
- Schema 浏览 → `DatabaseMetaData`
- SQL 执行（只读）→ 限行数、超时
- 查询历史持久化

### 2.5 环境控制台（`ServiceInstance` + `LogEntry`）

- 服务健康检查 → Docker API / 端口探活
- 日志流 → `docker logs --follow` → SSE
- AI 诊断 → 日志 + LLM → 修复建议

### 2.6 自动任务（`AutomationRule` + `ExecutionRecord`）

- 调度引擎（cron + 事件触发）
- 执行器（SQL / shell / 通知 / LLM）
- 执行历史 + 失败重试

### 2.7 通知（`useNotifications`）

- SSE / WebSocket 实时推送（角标）
- 持久化 + 已读状态

---

## 3. 技术栈决策

| 层 | 选型 | 理由 |
|----|------|------|
| 框架 | Spring Boot 3.3 | 生态成熟，Java 21 LTS，虚拟线程适合 IO 密集 |
| 构建 | Gradle 8 (Kotlin DSL) | 快、简洁 |
| Web | Spring WebFlux | SSE 流式、响应式适合日志推送 |
| 数据库 | PostgreSQL 16 + pgvector | 业务数据 + 向量同库，个人部署运维简单 |
| ORM | MyBatis-Flex 1.9 | 轻量、支持 pgvector 自定义类型 |
| 调度 | Quartz 2.5 | cron + 持久化 job store |
| 缓存/消息 | Redis 7（可选） | 通知 pub/sub、任务队列；个人部署可先跳过 |
| LLM 客户端 | Spring AI 1.0 | 内置 OpenAI/Anthropic/Ollama 适配、流式、Tool Calling |
| 文件解析 | Apache Tika 2.9 + Apache POI | PDF/Word/Excel/文本统一提取 |
| Docker 交互 | docker-java 3.3 | 容器启停、日志 |
| 认证 | Spring Security + JWT | 个人单用户最简 |
| 密钥加密 | Jasypt | BYO-Key 安全落库 |
| 文档 | springdoc-openapi | 契约对齐 |
| 测试 | JUnit 5 + Testcontainers + WireMock | 集成测试真实 PG/Docker |

**架构图：**

```
┌──────────────────────────────────────────────────────────────┐
│ nora-web (Vite + React)                                      │
│   fetch("/api/...") / EventSource("/api/.../stream")          │
└────────────────────────────┬─────────────────────────────────┘
                             │ HTTP / SSE
┌────────────────────────────▼─────────────────────────────────┐
│ nora-api (Spring Boot 3.3, Java 21, WebFlux)                  │
│                                                              │
│  controller → service → repository                          │
│  ├── rag        检索、索引、嵌入                              │
│  ├── chat       LLM 编排（SSE）+ 意图路由 + 工具调用          │
│  ├── file       上传、Tika 解析、预览                         │
│  ├── datasource JDBC 管理、SQL 执行                           │
│  ├── environment Docker 探活、日志采集                        │
│  └── automation Quartz 调度、执行器                           │
│                                                              │
│  llm/LlmClient → OpenAiClient / AnthropicClient / OllamaClient │
└──┬──────────────┬──────────────┬─────────────────────────────┘
   │              │              │
┌──▼─────┐   ┌───▼────┐   ┌────▼─────┐
│ PostgreSQL │   │ Redis  │   │ LLM Providers │
│ +pgvector  │   │ (可选)  │   │ (BYO-Key)      │
└────────────┘   └─────────┘   └────────────────┘
```

---

## 4. 数据库设计

### 4.1 核心表

```sql
-- Flyway: V1__init_schema.sql
CREATE EXTENSION IF NOT EXISTS vector;

-- 文件
CREATE TABLE file_item (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500),
    mime_type   VARCHAR(100),
    size_bytes  BIGINT,
    indexed     BOOLEAN DEFAULT false,
    created_at  TIMESTAMP DEFAULT now()
);

-- 知识库文档（file_item 的投影）
CREATE TABLE knowledge_doc (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    source      VARCHAR(20) NOT NULL,
    chunks      INTEGER DEFAULT 0,
    status      VARCHAR(20) DEFAULT 'processing',
    size        VARCHAR(20),
    quality     INTEGER DEFAULT 0,
    updated_at  TIMESTAMP DEFAULT now()
);

-- 知识库 chunk + 向量
CREATE TABLE knowledge_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT REFERENCES knowledge_doc(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content     TEXT,
    embedding   vector(1536),
    token_count INTEGER,
    created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_chunk_hnsw ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- 模型服务商
CREATE TABLE model_provider (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    protocol    VARCHAR(20) NOT NULL,
    endpoint    VARCHAR(500),
    api_key_enc TEXT,
    enabled     BOOLEAN DEFAULT true,
    models      TEXT[],
    status      VARCHAR(20) DEFAULT 'untested'
);

-- 对话
CREATE TABLE chat_session (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(255),
    created_at  TIMESTAMP DEFAULT now()
);

CREATE TABLE chat_message (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID REFERENCES chat_session(id) ON DELETE CASCADE,
    role        VARCHAR(10) NOT NULL,
    content     TEXT,
    steps       JSONB,
    sources     JSONB,
    created_at  TIMESTAMP DEFAULT now()
);

-- 数据源连接
CREATE TABLE db_connection (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    engine          VARCHAR(20) NOT NULL,
    host            VARCHAR(255),
    port            INTEGER,
    database        VARCHAR(100),
    username        VARCHAR(100),
    password_enc    TEXT,
    status          VARCHAR(20) DEFAULT 'untested'
);

-- 查询历史
CREATE TABLE query_history (
 
    BIGSERIAL PRIMARY KEY,
    connection_id  BIGINT REFERENCES db_connection(id) ON DELETE CASCADE,
    sql_text       TEXT NOT NULL,
    duration_ms    BIGINT,
    rows_affected  INTEGER,
    status         VARCHAR(20),
    executed_at    TIMESTAMP DEFAULT now()
);

-- 自动任务
CREATE TABLE automation_rule (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    trigger_expr VARCHAR(500),
    action       JSONB,
    enabled      BOOLEAN DEFAULT true,
    status       VARCHAR(20) DEFAULT 'active',
    last_run_at  TIMESTAMP,
    next_run_at  TIMESTAMP
);

CREATE TABLE execution_record (
    id          BIGSERIAL PRIMARY KEY,
    rule_id     BIGINT REFERENCES automation_rule(id) ON DELETE CASCADE,
    duration_ms BIGINT,
    status      VARCHAR(20),
    detail      TEXT,
    started_at  TIMESTAMP DEFAULT now()
);

-- 环境变量（BYO-Key）
CREATE TABLE env_var (
    key       VARCHAR(100) PRIMARY KEY,
    value_enc TEXT,
    secret    BOOLEAN DEFAULT true,
    note      VARCHAR(500)
);

-- 通知
CREATE TABLE notification (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(50),
    title      VARCHAR(255),
    body       TEXT,
    read       BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT now()
);
```

### 4.2 关键索引

```sql
CREATE INDEX idx_chunk_doc ON knowledge_chunk(doc_id);
CREATE INDEX idx_session_msg ON chat_message(session_id, created_at);
CREATE INDEX idx_exec_rule ON execution_record(rule_id, started_at DESC);
CREATE INDEX idx_notif_unread ON notification(read) WHERE NOT read;
```

---

## 5. REST API 契约

所有响应统一包装：

```json
{ "code": 0, "data": { ... }, "message": "ok" }
```

| 域 | 方法 | 端点 | 请求 | 响应 |
|----|------|------|------|------|
| RAG | GET | `/api/rag/docs` | — | `KnowledgeDoc[]` |
| RAG | GET | `/api/rag/index/stats` | — | `IndexStats` |
| RAG | POST | `/api/rag/search` | `{query, topK}` | `RetrievalResult[]` |
| RAG | POST | `/api/rag/citations` | `{query, topK}` | `Citation[]` |
| 文件 | POST | `/api/files/upload` | multipart | `FileItem` |
| 文件 | GET | `/api/files/{id}/preview` | — | `FilePreview` |
| 文件 | POST | `/api/files/{id}/index` | — | `{taskId}` |
| 文件 | DELETE | `/api/files?ids=` | ids | 204 |
| 对话 | POST | `/api/chat/sessions` | `{title}` | `ChatSession` |
| 对话 | GET | `/api/chat/sessions` | — | `ChatSession[]` |
| 对话 | POST | `/api/chat/sessions/{id}/messages` | `{content}` | SSE |
| 对话 | GET | `/api/chat/sessions/{id}/messages` | — | `ChatMessage[]` |
| 模型 | GET | `/api/models/providers` | — | `ModelProvider[]` |
| 模型 | POST | `/api/models/providers` | `ModelProvider` | `ModelProvider` |
| 模型 | PUT | `/api/models/providers/{id}` | — | `ModelProvider` |
| 模型 | DELETE | `/api/models/providers/{id}` | — | 204 |
| 模型 | POST | `/api/models/providers/{id}/test` | — | `{status}` |
| 数据源 | GET | `/api/datasources` | — | `DbConnection[]` |
| 数据源 | POST | `/api/datasources` | — | `DbConnection` |
| 数据源 | POST | `/api/datasources/{id}/test` | — | `{status}` |
| 数据源 | GET | `/api/datasources/{id}/schema` | — | `DbTable[]` |
| 数据源 | POST | `/api/datasources/{id}/query` | `{sql}` | `{rows, duration}` |
| 环境 | GET | `/api/environment/services` | — | `ServiceInstance[]` |
| 环境 | POST | `/api/environment/services/{id}/start` | — | `{status}` |
| 环境 | POST | `/api/environment/services/{id}/stop` | — | `{status}` |
| 环境 | GET | `/api/environment/logs/stream` | `?service=` | SSE |
| 自动任务 | GET | `/api/automations` | — | `AutomationRule[]` |
| 自动任务 | POST | `/api/automations` | — | `AutomationRule` |
| 自动任务 | PUT | `/api/automations/{id}` | — | `AutomationRule` |
| 自动任务 | POST | `/api/automations/{id}/run` | — | `ExecutionRecord` |
| 自动任务 | GET | `/api/automations/executions` | — | `ExecutionRecord[]` |
| 通知 | GET | `/api/notifications` | — | `Notification[]` |
| 通知 | PATCH | `/api/notifications/{id}/read` | — | 204 |
| 通知 | GET | `/api/notifications/stream` | — | SSE |

---

## 6. SSE 事件协议

### 6.1 对话流（对齐前端 `ChatStep`）

```
event: step
data: {"id":"1","type":"think","title":"分析意图","status":"completed"}

event: step
data: {"id":"2","type":"tool","title":"检索知识库","status":"running"}

event: delta
data: {"content":"根据 Redis 配置文档，"}

event: sources
data: [{"docName":"Redis配置","source":"file","chunkIndex":3,"score":0.93,"snippet":"..."}]

event: done
data: {"messageId":"uuid"}
```

### 6.2 日志流

```
event: log
data: {"time":"14:02:15","level":"warn","service":"postgres","message":"Connection pool 80%"}
```

### 6.3 通知流

```
event: notification
data: {"id":1,"type":"taskDone","title":"任务执行完成","body":"..."}
```

---

## 7. 模块划分

```
nora-api/src/main/java/com/nora/api/
├── NoraApplication.java
├── config/
│   ├── WebFluxConfig.java        # CORS + SSE 超时
│   ├── SecurityConfig.java       # JWT 过滤器链
│   ├── AsyncConfig.java          # 虚拟线程执行器
│   └── JasyptConfig.java         # 密钥加解密
├── controller/
│   ├── rag/          RagController, KnowledgeDocController
│   ├── file/         FileController
│   ├── chat/         ChatSessionController, ChatMessageController(SSE)
│   ├── model/        ModelProviderController
│   ├── datasource/   DbConnectionController, SchemaController, QueryController
│   ├── environment/  ServiceController, LogStreamController(SSE)
│   ├── automation/   AutomationRuleController, ExecutionController
│   └── notification/ NotificationController, NotificationStreamController(SSE)
├── service/
│   ├── rag/          RetrievalService, IndexingService, EmbeddingService
│   ├── chat/         ChatOrchestrationService, IntentRouter
│   ├── file/         FileStorageService, TextExtractionService, PreviewService
│   ├── datasource/   ConnectionPoolService, SchemaService, SqlExecutionService
│   ├── environment/  DockerService, LogCollectorService
│   └── automation/   SchedulerService, TriggerParser, ActionExecutor
├── domain/
│   ├── entity/       上述 13 张表实体
│   ├── repository/   MyBatis-Flex Mapper
│   └── enums/        KnowledgeSource, ChatRole, ExecStatus...
├── llm/
│   ├── LlmClient.java             # 统一接口：chat / stream / embed
│   ├── OpenAiClient.java
│   ├── AnthropicClient.java
│   ├── OllamaClient.java
│   └── ToolRegistry.java          # 前端技能中心对应
├── tools/                          # AI 工具（对应前端"AI 能力"）
│   ├── SqlQueryTool.java
│   ├── ServiceLogTool.java
│   ├── WebSearchTool.java
│   └── CodeExecutionTool.java
└── common/
    ├── ApiResponse.java, PageResult.java
    ├── GlobalExceptionHandler.java
    └── CryptoUtil.java
```

---

## 8. 关键设计决策

### 8.1 pgvector vs 独立向量库

| 方案 | 优势 | 劣势 |
|------|------|------|
| **pgvector（选定）** | 与业务同库事务一致；运维一个 PG | 千万级以上性能弱 |
| Milvus/Qdrant | 高性能可扩展 | 多一个服务，个人部署过重 |

预估规模 **<10 万 chunk**，HNSW 足够。

### 8.2 WebFlux vs MVC

前端有 3 个流式场景（对话/日志/通知），`Flux<ServerSentEvent>` 天然适配。JDBC/Docker 等阻塞调用用虚拟线程包装，不引入 `R2DBC`（pgvector 支持不成熟）。

### 8.3 BYO-Key 密钥安全

```
前端(明文) --HTTPS--> 后端 --Jasypt AES--> DB(密文)
                         ↓ 运行时解密
                    内存持有 → 调 LLM → 永不写日志
```

API 响应永远返回 `masked`（如 `sk-****...ff9e`），前端已有对应字段。

### 8.4 文件索引管线（异步）

```
upload → file_item入库 → 发消息/直接 @Async
  → Tika 提取文本 → 分块(500 tokens, overlap 50)
  → Embedding API → 批量写入 knowledge_chunk
  → 更新 knowledge_doc.status = indexed
```

失败 → `status = failed` + 通知。

### 8.5 前端零改动切换策略

前端调用已收敛到 `src/lib/services/ragService.ts`。后端 Phase 1 就绪后仅修改该文件 3 个函数体：

```typescript
export async function searchDocs(query: string, topK = 8): Promise<RetrievalResult[]> {
  const res = await fetch("/api/rag/search", { method: "POST", ... });
  return res.json();
}
```

其余组件零改动。同理后续按域逐步替换 mock store。

---

## 9. 分期实施计划

### Phase 1 · 最小可用（2-3 周）

**目标**：RAG 三端点 + 文件上传 → 前端 `ragService.ts` 真实化

| # | 任务 | 交付物 |
|---|------|--------|
| 1.1 | 脚手架 | Spring Boot 3.3 + Gradle + Flyway V1 |
| 1.2 | 文件上传 | `POST /api/files/upload`（本地目录） |
| 1.3 | 文本解析 | Tika + POI 提取 PDF/Word/Excel/文本 |
| 1.4 | 分块 | 500 token / overlap 50 |
| 1.5 | 嵌入 | Spring AI OpenAI Embedding |
| 1.6 | 检索 | pgvector HNSW cosine top-K |
| 1.7 | 统计 | `GET /api/rag/index/stats` |
| 1.8 | 前端切换 | `ragService.ts` 3 函数改 fetch |

**验收**：知识库检索测试返回真实 chunk snippet；索引状态为真实计数。

### Phase 2 · 对话闭环（2 周）

**目标**：SSE 流式对话 + 多模型 + 引用来源

| # | 任务 |
|---|------|
| 2.1 | Spring AI 集成三协议 |
| 2.2 | 模型管理 CRUD + 连通测试 |
| 2.3 | SSE 步骤/delta/sources 事件 |
| 2.4 | RAG 检索结果注入 system prompt |
| 2.5 | 会话与消息持久化 |
| 2.6 | 意图路由（沿用前端 `resolveIntent` 规则） |

### Phase 3 · 数据源 + 自动任务（2 周）

**目标**：SQL 只读查询 + Quartz 调度 + 通知闭环

| # | 任务 |
|---|------|
| 3.1 | JDBC 连接 CRUD + 测试 |
| 3.2 | `DatabaseMetaData` Schema 浏览 |
| 3.3 | SQL 只读执行（限行/超时/审计） |
| 3.4 | Quartz cron + 事件触发 |
| 3.5 | 执行器（SQL/通知/LLM） |
| 3.6 | 通知 SSE + 未读角标 |

### Phase 4 · 环境控制台（2 周）

**目标**：真实 Docker 服务 + 日志流 + AI 诊断

| # | 任务 |
|---|------|
| 4.1 | docker-java 容器列表/启停 |
| 4.2 | `docker logs --follow` → SSE |
| 4.3 | 日志上下文 → LLM 诊断 → 建议 |
| 4.4 | 诊断 → 一键创建自动任务 |

---

## 10. 里程碑与完成标准

| 里程碑 | 交付 | 验收 |
|--------|------|------|
| M1（Phase 1） | RAG 真实化 | 检索/统计/引用三端点全绿 |
| M2（Phase 2） | 对话真实化 | SSE 流式 + 引用 + 多模型 |
| M3（Phase 3） | SQL + 调度真实化 | 查询/任务/通知闭环 |
| M4（Phase 4） | 环境真实化 | Docker 控制 + 日志 + 诊断 |

---

## 11. 风险与对策

| 风险 | 对策 |
|------|------|
| pgvector HNSW 在 Windows 本地部署麻烦 | 用 Docker `pgvector/pgvector:pg16` 镜像 |
| WebFlux + 阻塞 JDBC 混用复杂 | 统一在 service 层用 `Mono.fromCallable(...).subscribeOn(boundedElastic)` |
| LLM API Key 泄漏 | Jasypt 加密 + 日志脱敏 + 响应 masked |
| SSE 长连接资源占用 | 设心跳、超时、断线重连机制 |
| Spring AI Anthropic/Ollama 版本变动 | 在 `llm/` 层封装统一接口，适配器隔离 |
| 自动任务执行 shell 命令安全 | 白名单 + 沙箱 + 超时 + 审计日志 |

---

## 12. 目录结构总览

```
nora-api/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── docker-compose.yml        # PG(pgvector) + Redis + nora-api
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__init_schema.sql
└── src/main/java/com/nora/api/
    ├── NoraApplication.java
    ├── config/ controller/ service/ domain/ llm/ tools/ common/
```

---

## 13. 立即启动步骤

```powershell
cd D:\claude\Nora
mkdir nora-api
cd nora-api
gradle init --type java-application
```

骨架创建后：

1. 添加 Spring Boot 插件与依赖
2. 编写 `application.yml`（数据库连接、上传目录、Jasypt 盐）
3. 编写 `V1__init_schema.sql`
4. 实现 RagController 三个端点（先返回静态 mock，再替换为 pgvector）
5. 前端 `ragService.ts` 切换 fetch
6. `pnpm typecheck && pnpm test && pnpm build` 回归