# Nora API

Nora 个人工作台 Java 后端。当前为立项阶段，完整设计文档见：

- [docs/architecture-v2.md](docs/architecture-v2.md) — **微服务架构设计（当前基准）**
- [docs/agent-design-research.md](docs/agent-design-research.md) — **Agent 设计调研**（ReAct/Reflexion/ReWOO/Voyager/SWE-agent 论文 + Anthropic/Cognition/LangGraph/OpenAI 实践）
- [docs/frontend-impact-analysis.md](docs/frontend-impact-analysis.md) — **前端影响分析**（零改动/需改/需新增三分类 + 回退策略）
- [docs/nora-api-initiation-2026-09-04.md](docs/nora-api-initiation-2026-09-04.md) — 领域需求反推 + API 契约（单体版，保留作参考）

## 定位

- 微服务架构：7 个服务（gateway / file / rag / **agent** / datasource / env / automation）
- Spring Boot 3.3 · Java 21 · Spring Cloud Alibaba（Nacos + Dubbo + Sentinel）
- **LangChain4j**（Agent 循环 / AiServices / Tools / RAG / ChatMemory）
- PostgreSQL 16 + pgvector · RocketMQ · Redis
- Maven 多模块 + Docker Compose

## 当前状态

| 阶段 | 状态 |
|------|------|
| 立项设计 | ✅ 已完成（v2 微服务版） |
| 项目骨架 | ✅ 已创建（Maven 多模块：16 模块，`mvn install` 全绿，27 tests） |
| Phase 0 基础设施 | ✅ 已就绪（docker-compose dev-basic：PG+pgvector / Redis / Nacos；gateway→agent 路由冒烟通过） |

## 快速开始

```bash
# 基础设施（PG :5432 / Redis :6379 / Nacos :8848）
cd nora-api && docker compose --profile dev-basic up -d

# 构建（16 模块全部构建 + 测试）
mvn install

# 起服务（示例：agent + gateway）
java -jar services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar
java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar
# 验证：curl http://localhost:8080/api/chat/health → ok（经 Nacos 服务发现转发）
```

完整架构见 [docs/architecture-v2.md](docs/architecture-v2.md)。

与 `nora-web` 完全独立，各自安装 / 启动。API 契约以 v1 文档第 5、6 章 + v2 文档第 8 章为准。