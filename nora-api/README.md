# Nora API

Nora 个人工作台 Java 后端。当前为立项阶段，完整设计文档见：

- [docs/nora-api-initiation-2026-09-04.md](docs/nora-api-initiation-2026-09-04.md)

## 定位

- Spring Boot 3.3 · Java 21 · WebFlux
- PostgreSQL 16 + pgvector
- Spring AI（OpenAI / Anthropic / Ollama）
- Quartz 调度 + Docker 集成

## 当前状态

| 阶段 | 状态 |
|------|------|
| 立项设计 | ✅ 已完成 |
| 项目骨架 | ⏳ 待创建 |
| Phase 1 RAG | ⏳ 待实施 |

与 `nora-web` 完全独立，各自安装 / 启动。API 契约以设计文档第 5、6 章为准。