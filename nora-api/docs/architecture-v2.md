# Nora API · 微服务架构设计 v2

> 状态：立项设计稿（v2，替代单体方案）  
> 日期：2026-09-04 · 前端基线：`18ffa9c`  
> 前置文档：[nora-api-initiation-2026-09-04.md](nora-api-initiation-2026-09-04.md)（保留作为领域需求反推与 API 契约来源）

---

## 0. 变更摘要

| 项 | v1 单体方案 | v2 微服务方案 |
|----|------------|--------------|
| 部署形态 | 单个 Spring Boot 应用 | 6 个可独立部署服务 + 2 个平台组件 |
| LLM 接入 | Spring AI | **LangChain4j**（Req/Ai/Tool/Serve 四层） |
| 服务治理 | 无 | **Spring Cloud Alibaba**（Nacos + Dubbo + Sentinel） |
| 网关 | 无独立网关 | **Spring Cloud Gateway**（统一入口 + SSE 透传） |
| 数据一致性 | 单库事务 | Saga + 最终一致 + outbox |
| 部署 | 单 jar | Docker Compose / K8s，每服务独立镜像 |

---

## 1. 总体架构

```
                       ┌─────────────────────┐
                       │     nora-web        │
                       │  (Vite + React)     │
                       └──────────┬──────────┘
                                  │ HTTP / SSE
                    ┌─────────────▼─────────────┐
                    │  gateway-service :8080    │
                    │  Spring Cloud Gateway     │
                    │  路由/鉴权/限流/SSE透传     │
                    └──────┬──────┬──────┬─────┘
                           │      │      │
        ┌──────────────────▼┐  ┌──▼───────────┐  ┌▼────────────────┐
        │ file-service      │  │ rag-service  │  │ agent-service   │
        │ :8081             │  │ :8082        │  │ :8083           │
        │ 文件/上传/解析     │  │ 切块/嵌入/检索 │  │ LangChain4j编排  │
        └────┬──────────────┘  └──┬───────────┘  └┬────────────────┘
             │                    │                │
        ┌────▼──────────────┐  ┌──▼────────────┐  │
        │ datasource-service│  │ env-service    │  │
        │ :8084             │  │ :8085          │  │
        │ JDBC/SQL执行      │  │ Docker/日志     │  │
        └────────────────────┘  └───────────────┘  │
             │                                      │
        ┌────▼──────────────┐                       │
        │ automation-service│                       │
        │ :8086             │                       │
        │ Quartz/触发/执行   │                       │
        └────────────────────┘                       │
                                                    │
                    ┌───────────────────────────────▼─┐
                    │ platform components              │
                    │ ├── nacos :8848                 │
                    │ ├── rocketmq (or redis stream)  │
                    │ ├── postgres:pgvector :5432     │
                    │ └── redis :6379                 │
                    └─────────────────────────────────┘
```

---

## 2. 服务拆分

| 服务 | 端口 | 职责 | 对应前端页面 |
|------|------|------|-------------|
| gateway-service | 8080 | 统一入口、JWT 鉴权、路由、限流、SSE 透传 | 全部 |
| file-service | 8081 | 文件上传、Tika 解析、预览生成 | /files |
| rag-service | 8082 | 文档切块、Embedding、pgvector 检索 | /knowledge |
| agent-service | 8083 | LangChain4j Agent 编排（感知→规划→工具→反思）、SSE 流式 | /chat |
| datasource-service | 8084 | JDBC 连接管理、Schema 浏览、SQL 执行 | /data-sources |
| env-service | 8085 | Docker 探活、日志采集、AI 诊断 | /environments |
| automation-service | 8086 | Quartz 调度、触发器、执行器 | /automations |

**拆分原则：**

1. 按前端路由域拆，与页面/团队心智一致
2. 每个服务可独立部署、独立扩容（如 rag-service 需要更多 CPU 做嵌入）
3. 服务间通信用 **Dubbo**（内部同步调用）+ **RocketMQ**（异步事件）
4. 前端所有请求都走 gateway，服务之间不直接暴露端口
```
---

## 3. 技术栈（微服务版）

| 层 | 选型 | 理由 |
|----|------|------|
| 基础框架 | **Spring Boot 3.3** | Java 21 LTS、生态标准 |
| 微服务治理 | **Spring Cloud Alibaba 2023.x** | Nacos 注册/配置中心、Sentinel 限流熔断 |
| 内部 RPC | **Apache Dubbo 3.3** | 高性能内部调用，接口即契约 |
| 网关 | **Spring Cloud Gateway** | 路由、JWT 鉴权、限流、SSE 透传 |
| **LLM 框架** | **LangChain4j 1.x** | AiServices / Tools / RAG / Memory 一站式 |
| 数据库 | PostgreSQL 16 + pgvector | 业务 + 向量同库 |
| ORM | MyBatis-Flex 1.9 | 轻量、pgvector 类型扩展 |
| 消息 | RocketMQ 5（可降级 Redis Stream） | 跨服务事件、outbox |
| 调度 | Quartz（automation-service 内） | cron 持久化 job |
| 文件解析 | Apache Tika 2.9 + POI | PDF/Word/Excel/文本 |
| Docker 交互 | docker-java 3.3 | 容器启停、日志 |
| 密钥加密 | Jasypt | BYO-Key 落库加密 |
| 可观测 | Micrometer + OpenTelemetry + Grafana LGTM | 日志/指标/链路 |
| 部署 | Docker Compose（开发）→ K8s（可选） | 每服务独立镜像 |
| 测试 | Testcontainers + WireMock | 每服务独立集成测试 |

---

## 4. LangChain4j Agent 设计（核心）

LangChain4j 是 Java 生态最成熟的 LLM 编排框架，四个核心能力全部用上：

### 4.1 依赖

```kotlin
// agent-service pom.xml
dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.1")
    implementation("dev.langchain4j:langchain4j-anthropic:1.0.1")
    implementation("dev.langchain4j:langchain4j-ollama:1.0.1")
    implementation("dev.langchain4j:langchain4j-reactor:1.0.1")  // Flux 流式
    implementation("dev.langchain4j:langchain4j-pgvector:1.0.1") // 向量库集成
}
```

### 4.2 AiServices（声明式 Agent 接口）

```java
public interface NoraAgent {

    @SystemMessage("""
        你是 Nora 个人工作台的助手。回答必须：
        1. 优先使用检索到的知识库内容
        2. 引用来源时使用 [[docName]] 标记
        3. 如果工具返回错误，如实说明并给出下一步建议
        """)
    Flux<String> chat(@UserMessage String message);
}
```

### 4.3 Tools（Agent 工具 = 前端"AI 能力"）

```java
public class NoraTools {

    @Tool("查询已连接的数据库，只执行 SELECT 语句")
    public String executeSql(
        @P("数据源名称") String datasourceName,
        @P("SQL 查询语句") String sql) {
        return dubboSqlClient.executeReadOnly(datasourceName, sql);
    }

    @Tool("读取指定服务最近 N 条日志")
    public String readServiceLogs(
        @P("服务名") String service,
        @P("条数") int limit) {
        return dubboLogClient.tail(service, limit);
    }

    @Tool("创建自动修复任务")
    public String createAutomation(
        @P("任务名称") String name,
        @P("触发条件") String trigger,
        @P("执行动作") String action) {
        return dubboAutomationClient.addRule(name, trigger, action);
    }
}
```

### 4.4 RAG（rag-service 侧）

```java
// 嵌入
EmbeddingModel openAiEmbedding = OpenAiEmbeddingModel.builder()
    .apiKey(key)
    .dimensions(1536)
    .build();

// 向量库
EmbeddingStore<TextSegment> store = PgVectorEmbeddingStore.builder()
    .host("postgres")
    .port(5432)
    .database("nora")
    .table("knowledge_chunk")
    .dimension(1536)
    .build();

// 检索管道
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(store)
    .embeddingModel(openAiEmbedding)
    .maxResults(8)
    .minScore(0.5)
    .build();

// 注入 agent-service 的 NoraAgent
NoraAgent agent = AiServices.builder(NoraAgent.class)
    .chatLanguageModel(model)
    .contentRetriever(retriever)
    .tools(new NoraTools())
    .chatMemoryProvider(sessionId -> MessageWindowChatMemory.withMaxMessages(20))
    .build();
```

### 4.5 多模型路由（LangChain4j ModelRouter 模式）

```java
public class ModelRouter {
    private final Map<String, ChatModel> models;

    public ChatModel route(String protocol, String modelName) {
        return switch (protocol) {
            case "openai"    -> openAiModel(modelName);
            case "anthropic" -> anthropicModel(modelName);
            case "ollama"    -> ollamaModel(modelName);
            default          -> throw new IllegalArgumentException(protocol);
        };
        // model 字段来自 model_provider 表，用户在设置页自由切换
    }
}
```

### 4.6 LangChain4j 模块归属

| 模块 | 所在服务 | 说明 |
|------|---------|------|
| ChatModel / StreamingChatModel | agent-service | 对话生成 |
| EmbeddingModel | rag-service | 文档向量化 |
| EmbeddingStore（pgvector） | rag-service | 向量存储 |
| ContentRetriever | rag-service | 检索管道 |
| Tools | agent-service | 工具调用（跨服务走 Dubbo） |
| ChatMemoryProvider | agent-service | 会话记忆 |

**注意**：`ContentRetriever` 在 rag-service，agent-service 通过 Dubbo 接口调用检索，把检索结果注入 prompt，不直接读 pgvector。

---


---

### 4.8 调研驱动的 Agent 设计（2026-09-04）

> 完整调研来源见 [agent-design-research.md](agent-design-research.md)（ReAct / Reflexion / ReWOO / Voyager / SWE-agent / LATS 论文 + Anthropic / Cognition / LangGraph / OpenAI Agents SDK 实践）。

#### 4.8.1 执行模式：ReAct 基础 + ReWOO 优化

| 场景 | 模式 | 理由 |
|------|------|------|
| 开放式对话（`/chat`） | **ReAct** | Thought→Action→Observation 交错，天然对齐前端 `ChatStep`（think/tool） |
| 多独立工具请求 | **ReWOO** | Planner 一次规划 → Worker 并行执行 → Solver 汇总，省 5× token |
| 固定管线（文件索引/环境诊断） | **Workflow（非 Agent）** | 预定义代码路径，不让 LLM 决定流程 |

**判断标准**：用户请求涉及 >3 个独立工具调用且相互无依赖 → ReWOO；否则 ReAct。

#### 4.8.2 多 Agent 决策：不引入

依据 Cognition《Don't Build Multi-Agents》三原则：

1. Share full context, not individual messages
2. Actions carry implicit decisions
3. Single-threaded > multi-agent

Nora 的 agent-service 保持**单线程执行循环**。若未来需要 subagent，仅用于只读检索类任务（不产生副作用），且必须共享完整父上下文。

#### 4.8.3 工具接口：Agent-Computer Interface (ACI)

依据 SWE-agent 论文——LLM 是新的最终用户，需要专门设计接口：

| NoraTools 方法 | ACI 设计 |
|---------------|----------|
| `executeSql` | 结果截断 50 行 + 列类型标注 + 空值标注 |
| `readServiceLogs` | 去噪（过滤 DEBUG）+ ERROR 行高亮 + 最近 N 条 |
| `searchKnowledge` | 返回 doc name + score + chunk index，不返回全文 |
| `createAutomation` | 返回结构化确认（ruleId + name），不返回执行细节 |

**原则**：工具返回值是"为 LLM 设计的信息"，不是原始 API 输出。

#### 4.8.4 记忆：三层

| 层 | 实现 | 依据 |
|----|------|------|
| 短期会话 | `MessageWindowChatMemory`（20 条） | LangChain4j 内置 |
| 反思记忆 | 失败轨迹 → 自然语言 critique → episodic 存储 | Reflexion 论文 |
| 技能库 | 成功 tool-call 链 → 按 embedding 索引 → 相似请求复用 | Voyager 论文 |

#### 4.8.5 安全：四层 Guardrail

参考 OpenAI Agents SDK 分层：

```
Input Guardrail      → 检查用户输入（prompt injection / 越权请求）
    ↓
Tool Input Guardrail → SQL 只读校验 / 文件路径白名单 / Docker 命令白名单
    ↓
Tool Output Guardrail→ 敏感信息脱敏（API key / 密码 / IP）
    ↓
Output Guardrail     → 最终回答安全检查
```

每层触发 tripwire → 中断执行 → 返回结构化错误 → 前端 `ChatStep(status=failed)`。

#### 4.8.6 状态：逐 step Checkpoint

参考 LangGraph Checkpointer：

- 每个 agent step（think / tool call / observation）执行后立即持久化到 `agent_step` 表
- 不等整轮结束才写入
- 支持断点恢复、时间旅行调试、轨迹审计
- 高风险工具调用前 interrupt → 等待用户批准 → 继续执行

#### 4.8.7 LangChain4j AgenticServices 映射

LangChain4j 已提供声明式 Agentic 原语（`AgenticServices` 类），与 Nora 需求映射：

| LangChain4j API | Nora 用途 |
|-----------------|----------|
| `AgenticServices.agentBuilder()` | 构建 NoraAgent |
| `AgenticServices.loopBuilder()` + `@ExitCondition` | ReAct 循环（条件退出） |
| `AgenticServices.sequenceBuilder()` | 固定 Workflow 管线 |
| `AgenticServices.parallelBuilder()` | ReWOO 并行 Worker |
| `AgenticServices.humanInTheLoopBuilder()` | 高风险工具审批 |
| `AgentListener` / `AgentMonitor` | 逐 step 观测 → SSE 推送 |
| `AgenticScope` | 跨 step 共享状态 |

---

### 4.9 Agent 状态表（新增）

```sql
-- 逐 step 轨迹持久化（参考 LangGraph checkpointing）
CREATE TABLE agent_step (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL,
    step_index  INTEGER NOT NULL,
    step_type   VARCHAR(20) NOT NULL,      -- think / tool_call / observation / reflection
    content     TEXT,
    tool_name   VARCHAR(100),
    tool_input  JSONB,
    tool_output JSONB,
    status      VARCHAR(20),
    duration_ms BIGINT,
    created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_agent_step_session ON agent_step(session_id, step_index);

-- Reflexion 反思记忆
CREATE TABLE agent_reflection (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL,
    task_signature VARCHAR(255),
    reflection  TEXT NOT NULL,
    created_at  TIMESTAMP DEFAULT now()
);

-- Voyager 式技能库
CREATE TABLE agent_skill (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    tool_chain  JSONB NOT NULL,
    embedding   vector(1536),
    success_count INTEGER DEFAULT 0,
    created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_skill_embedding ON agent_skill USING hnsw (embedding vector_cosine_ops);
```

---

### 4.10 修订后的 Phase 2 实施计划

| # | 任务 | 依据 |
|---|------|------|
| 2.1 | `AgenticServices.agentBuilder()` + `loopBuilder()` ReAct 循环 | LangChain4j AgenticServices |
| 2.2 | SSE：step/delta/sources/done 四类事件，逐 step 持久化 | LangGraph checkpoint |
| 2.3 | Tools + ACI 格式化（截断/摘要/去噪） | SWE-agent |
| 2.4 | 四层 Guardrail（input/tool-in/tool-out/output） | OpenAI Agents SDK |
| 2.5 | ChatMemory + `agent_reflection` 失败反思 | Reflexion |
| 2.6 | ReWOO 并行模式（>3 独立工具时自动切换） | ReWOO 论文 |
| 2.7 | `agent_skill` 技能沉淀（成功调用链入库） | Voyager |
| 2.8 | 会话与消息持久化 | — |

---

### 4.11 参考文献

| 类型 | 来源 |
|------|------|
| 论文 | ReAct (Yao et al., 2022, arXiv:2210.03629) |
| 论文 | Reflexion (Shinn et al., 2023, arXiv:2303.11366) |
| 论文 | ReWOO (Xu et al., 2023, arXiv:2305.18323) |
| 论文 | Voyager (Wang et al., 2023) |
| 论文 | SWE-agent (Yang et al., 2024, arXiv:2405.15793) |
| 论文 | LATS (Xie et al., 2023, arXiv:2310.04406) |
| 实践 | Anthropic: Building Effective Agents (2024-12) |
| 实践 | Cognition: Don't Build Multi-Agents (2025-06) |
| 实践 | LangGraph Checkpointers 文档 |
| 实践 | OpenAI Agents SDK 文档 |
| 实践 | LangChain4j AgenticServices 文档 |

## 5. 服务间通信

### 5.1 Dubbo（同步 RPC）

内部服务接口定义在公共模块 `nora-api`（api 包）：

```java
// nora-api/rag-api/src/main/java/com/nora/rag/api/RagService.java
public interface RagService {
    List<RetrievalResult> search(SearchRequest request);
    IndexStatistics getIndexStats();
}

// nora-api/file-api/src/main/java/com/nora/file/api/FileService.java
public interface FileService {
    FileItem upload(FileUploadRequest request);
    PreviewResult preview(Long fileId);
}
```

服务提供方实现接口并注册到 Nacos，消费方 `@DubboReference` 注入。

### 5.2 RocketMQ（异步事件）

| Topic | 生产者 | 消费者 | 事件 |
|-------|--------|--------|------|
| `file.uploaded` | file-service | rag-service | 文件上传完成 → 触发索引 |
| `doc.indexed` | rag-service | agent-service、automation-service | 文档可检索 |
| `sql.executed` | datasource-service | automation-service | 查询完成 |
| `log.error` | env-service | automation-service | 触发告警任务 |
| `task.executed` | automation-service | notification | 任务完成通知 |
| `notification.created` | automation-service、env-service | notification | 推送通知 |

**Outbox 模式**：业务写库与事件发布在同一本地事务中先写 `outbox` 表，后台任务轮询发送，保证不丢事件。

### 5.3 Saga（跨服务最终一致）

以"文件上传 → 索引 → 可检索"为例：

```
file-service: INSERT file_item + INSERT outbox(file.uploaded)
    → MQ → rag-service: 插入 knowledge_doc(processing) + 异步嵌入
    → rag-service: 更新 indexed + MQ(doc.indexed)
    → agent-service / automation-service 消费（仅更新缓存/计数）
```

补偿：嵌入失败 → knowledge_doc.status = failed → 发 `doc.index.failed` → notification 推送。
```
---

## 6. 目录结构（Maven 多模块）

```
nora-api/
├── pom.xml                                # 父 POM：统一版本管理
├── docker-compose.yml                     # PG + Redis + Nacos + RocketMQ + 全部服务
├── services/
│   ├── gateway-service/
│   │   ├── pom.xml
│   │   └── src/main/java/com/nora/gateway/
│   │       ├── GatewayApplication.java
│   │       ├── config/SecurityConfig.java
│   │       └── route/ApiRouteConfiguration.java
│   │
│   ├── file-service/
│   │   ├── pom.xml
│   │   └── src/main/java/com/nora/file/
│   │       ├── FileApplication.java
│   │       ├── controller/FileController.java
│   │       ├── service/FileStorageService.java
│   │       ├── service/TextExtractionService.java      # Tika
│   │       ├── service/PreviewService.java
│   │       ├── domain/                                    # MyBatis-Flex
│   │       └── mq/FileEventProducer.java                # outbox → MQ
│   │
│   ├── rag-service/
│   │   ├── pom.xml
│   │   └── src/main/java/com/nora/rag/
│   │       ├── RagApplication.java
│   │       ├── controller/RagController.java
│   │       ├── service/ChunkingService.java
│   │       ├── service/EmbeddingService.java           # LangChain4j Embedding
│   │       ├── service/RetrievalService.java
│   │       ├── store/PgVectorStore.java                # LangChain4j pgvector
│   │       └── mq/DocIndexConsumer.java                # 消费 file.uploaded
│   │
│   ├── agent-service/
│   │   ├── pom.xml
│   │   └── src/main/java/com/nora/agent/
│   │       ├── AgentApplication.java
│   │       ├── controller/AgentController.java          # SSE
│   │       ├── agent/NoraAgent.java                   # LangChain4j AiServices
│   │       ├── agent/ModelRouter.java
│   │       ├── tools/NoraTools.java                   # @Tool
│   │       ├── memory/ChatMemoryProvider.java
│   │       └── service/AgentOrchestrationService.java
│   │
│   ├── datasource-service/
│   │   ├── pom.xml
│   │   └── src/main/java/com/nora/datasource/
│   │       ├── DatasourceApplication.java
│   │       ├── controller/ConnectionController.java
│   │       ├── controller/SchemaController.java
│   │       ├── controller/QueryController.java
│   │       ├── pool/ConnectionPoolManager.java
│   │       └── guard/SqlGuard.java                    # 只读校验
│   │
│   ├── env-service/
│   │   ├── pom.xml
│   │   └── src/main/java/com/nora/env/
│   │       ├── EnvApplication.java
│   │       ├── controller/ServiceController.java
│   │       ├── controller/LogStreamController.java    # SSE
│   │       ├── docker/DockerClientFactory.java
│   │       └── service/DiagnosisService.java          # 调 agent-service
│   │
│   └── automation-service/
│       ├── pom.xml
│       └── src/main/java/com/nora/automation/
│           ├── AutomationApplication.java
│           ├── controller/AutomationController.java
│           ├── scheduler/QuartzSchedulerService.java
│           ├── trigger/EventTriggerListener.java      # 消费 MQ 事件
│           ├── executor/ActionExecutor.java
│           └── mq/TaskEventConsumer.java
│
├── api/                                   # Dubbo 接口 + DTO（独立 jar）
│   ├── rag-api/
│   ├── file-api/
│   ├── datasource-api/
│   ├── env-api/
│   └── automation-api/
│
├── common/
│   ├── nora-common/                      # ApiResponse、异常、工具
│   └── nora-security/                    # JWT 工具、过滤器
│
└── docs/
    ├── architecture-v2.md
    └── nora-api-initiation-2026-09-04.md
```

**Maven 而非 Gradle 的理由**：多模块微服务 + Dubbo 接口 jar 分发，Maven 的 reactor + BOM 管理更成熟直观。

---

## 7. 数据库策略

### 7.1 每服务独立 schema（逻辑隔离）

```
postgres (single instance)
├── schema_file      → file-service
├── schema_rag       → rag-service
├── schema_agent      → agent-service
├── schema_datasource→ datasource-service
├── schema_env       → env-service
└── schema_automation→ automation-service
```

开发期单实例分 schema（简化运维），生产可平滑拆为独立实例（连接串只改配置）。

### 7.2 跨服务数据所有权

| 表 | 归属服务 | 其他服务访问方式 |
|----|---------|----------------|
| `file_item` | file-service | Dubbo `FileService.getById()` |
| `knowledge_doc/chunk` | rag-service | Dubbo `RagService.search()` |
| `model_provider` | agent-service | Dubbo `ModelService.list()` |
| `chat_session/message` | agent-service | 直接 REST |
| `db_connection` | datasource-service | Dubbo `DatasourceService.test()` |
| `service_instance/log` | env-service | Dubbo `EnvService.tail()` |
| `automation_rule/execution` | automation-service | 直接 REST |
| `notification` | automation-service | SSE 推送 |

**禁止跨 schema JOIN**，需要聚合时由消费方组装或前端并行请求。

---

## 8. 网关设计

```yaml
# gateway-service application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: file
          uri: lb://file-service
          predicates:
            - Path=/api/files/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 20
                redis-rate-limiter.burstCapacity: 40
        - id: rag
          uri: lb://rag-service
          predicates:
            - Path=/api/rag/**
        - id: chat-sse
          uri: lb://agent-service
          predicates:
            - Path=/api/chat/**
          filters:
            - name: SseTimeout            # 自定义 filter
              args:
                timeout: 300s
        - id: env-logs
          uri: lb://env-service
          predicates:
            - Path=/api/environment/logs/stream
        - id: automation
          uri: lb://automation-service
          predicates:
            - Path=/api/automations/**
```

**SSE 透传**：Gateway 必须设置：
- `spring.cloud.gateway.httpclient.response-timeout: 300s`
- 自定义 filter 清除 `Transfer-Encoding`，保持 `text/event-stream` 长连接

---

## 9. Docker Compose（开发环境）

```yaml
version: "3.9"
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: nora
      POSTGRES_USER: nora
      POSTGRES_PASSWORD: nora
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  nacos:
    image: nacos/nacos-server:v2.3.2
    environment:
      MODE: standalone
    ports: ["8848:8848", "9848:9848"]

  rocketmq-namesrv:
    image: apache/rocketmq:5.1.4
    command: sh mqnamesrv
    ports: ["9876:9876"]

  rocketmq-broker:
    image: apache/rocketmq:5.1.4
    command: sh mqbroker -n rocketmq-namesrv:9876
    ports: ["10911:10911"]

  gateway-service:
    build: services/gateway-service
    ports: ["8080:8080"]
    depends_on: [nacos, postgres, redis]

  file-service:
    build: services/file-service
    depends_on: [nacos, postgres, rocketmq-broker]

  rag-service:
    build: services/rag-service
    depends_on: [nacos, postgres]

  agent-service:
    build: services/agent-service
    depends_on: [nacos, postgres]

  datasource-service:
    build: services/datasource-service
    depends_on: [nacos, postgres]

  env-service:
    build: services/env-service
    depends_on: [nacos]

  automation-service:
    build: services/automation-service
    depends_on: [nacos, postgres, rocketmq-broker]

volumes:
  pgdata:
```

---

## 10. 父 POM 依赖管理（核心 BOM）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>3.3.4</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-alibaba-dependencies</artifactId>
      <version>2023.0.1.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-bom</artifactId>
      <version>1.0.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.dubbo</groupId>
      <artifactId>dubbo-bom</artifactId>
      <version>3.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

---

## 11. LangChain4j 与前端契约对齐

| 前端字段 | LangChain4j 生成方式 |
|---------|---------------------|
| `ChatStep(type=think)` | `TokenStream` 的 `onPartialResponse` 前自定义阶段事件 |
| `ChatStep(type=tool)` | `TokenStream.onToolExecution`（需 1.x 监听器扩展） |
| `sources[]` | `ContentRetriever` 检索结果 → `EmbeddingMatch.metadata()` |
| `isTyping` | SSE `delta` 事件持续推送 |
| `ModelProvider.protocol` | `ModelRouter.route(protocol, model)` |

SSE 事件保持与 v1 文档第 6 章完全一致（step/delta/sources/done）。

---

## 12. 实施阶段（微服务版）

### Phase 0 · 基础设施（1 周）

| # | 任务 |
|---|------|
| 0.1 | Maven 多模块骨架（父 POM + common + api 模块） |
| 0.2 | docker-compose 起 PG/Nacos/RocketMQ/Redis |
| 0.3 | gateway-service 骨架 + 路由通 |
| 0.4 | CI：每模块 `mvn verify` |

### Phase 1 · RAG 链路（2 周）

| # | 任务 |
|---|------|
| 1.1 | file-service：上传 + Tika 解析 |
| 1.2 | file-service → MQ → rag-service 消费 |
| 1.3 | rag-service：LangChain4j Chunk + Embedding |
| 1.4 | rag-service：pgvector 存储 + 检索 |
| 1.5 | gateway 路由 `/api/rag/**` 通 |
| 1.6 | 前端 `ragService.ts` 切换 fetch |

### Phase 2 · Agent 链路（2-3 周，依据调研修订）

| # | 任务 |
|---|------|
| 2.1 | agent-service：LangChain4j AiServices + ModelRouter + Agent 循环 |
| 2.2 | SSE：step/delta/sources/done 四类事件 |
| 2.3 | Tools：SQL 查询、日志读取、创建任务（Dubbo）→ 实现 ReAct 循环 |
| 2.4 | ChatMemory（MessageWindowChatMemory 20 条）+ Agent 轨迹持久化 |
| 2.5 | 会话持久化 |

### Phase 3 · 数据源 + 任务 + 通知（2 周）

| # | 任务 |
|---|------|
| 3.1 | datasource-service：连接 CRUD + 测试 |
| 3.3 | Schema 浏览 + 只读 SQL 执行 |
| 3.4 | automation-service：Quartz + 事件触发 |
| 3.5 | 通知 SSE + 未读角标 |

### Phase 4 · 环境控制台（2 周）

| # | 任务 |
|---|------|
| 4.1 | env-service：docker-java 容器列表/启停 |
| 4.2 | 日志流 SSE（gateway 透传） |
| 4.3 | AI 诊断：日志 → agent-service（LangChain4j ReAct）→ 建议 |
| 4.4 | 诊断 → 创建自动化修复任务 |

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| 7 个服务本地开发启动慢 | docker-compose profiles：`dev-basic`（PG+Nacos）+ `dev-all` |
| RocketMQ 运维成本 | 个人部署降级为 Redis Stream（抽象 EventBus 接口） |
| LangChain4j 版本演进快 | 锁定 BOM 1.x，升级走独立 PR + 回归 |
| SSE 过 Gateway 透传问题 | 自定义 `SseTimeoutFilter` + 集成测试覆盖 |
| Dubbo 接口 jar 同步 | `api/` 模块版本化（1.0.0-SNAPSHOT → 1.0.0） |
| 单实例多 schema 迁移 | Flyway 每服务独立 `migration` 目录 |

---

## 14. 与 v1 单体方案的关系

v1 文档保留以下内容作为**领域需求与 API 契约的事实来源**：

- 第 2 章：前端契约反推清单（7 域映射）
- 第 5 章：REST API 契约（38 端点）
- 第 6 章：SSE 事件协议

v2 覆盖以下内容：

- 架构：单体 → 微服务
- LLM：Spring AI → **LangChain4j**
- 部署：单 jar → Docker Compose / K8s
- 通信：进程内 → Dubbo + RocketMQ

**后续以本文档为实施基准。**