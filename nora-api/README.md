# Nora API

Nora 个人工作台 Java 后端。当前为立项阶段，完整设计文档见：

- [docs/architecture-v2.md](docs/architecture-v2.md) — **微服务架构设计（当前基准）**
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
| 项目骨架 | ⏳ 待创建 |
| Phase 0 基础设施 | ⏳ 待实施 |

与 `nora-web` 完全独立，各自安装 / 启动。API 契约以 v1 文档第 5、6 章 + v2 文档第 8 章为准。