# Nora

AI 驱动的个人工作台：RAG 知识库 + 可调用 SQL/日志/文件/环境工具的对话 Agent + 数据源/环境/自动任务管理。

## 目录结构

```
Nora/
├── nora-web/         # 前端 — Vite 7 + React 18 + Tailwind + Zustand（3001）
├── nora-api/         # 后端 — Spring Boot 3.3 (Java 21) 微服务，设计文档见 nora-api/docs/
└── phone-album-mcp/  # 手机相册 MCP（独立仓库：Android App + 中继服务）
```

前后端独立，分别 `pnpm install` / `pnpm dev`；后端用 Maven 构建，Docker Compose 起基础设施（PG+pgvector / Redis / Nacos / Kafka）。

## 当前状态（2026-09-20）

**能实际使用的单用户工作台**，不建议再给未经验收的完成度百分比（历史进度见 PROGRESS-REVIEW-2026-09-06.md，其口径已过时）。

| 端 | 状态 |
|----|------|
| 后端 | 8 个微服务（gateway/file/rag/agent/datasource/env/automation/notification）；Kafka 事件通知已上线（替换文档中计划的"通知 SSE"）；令牌登录（NORA_AUTH_TOKEN，缺省免登录） |
| 前端 | 10 个工作台页面 + 登录页；全部经统一 envelope 接入真实后端；USE_BACKEND 开关保留本地 mock 路径 |

**核心能力**：对话 Agent（SSE 流式/工具审批/断线重连/上下文压缩）、文件中心（上传/文件夹/预览/回收站/RAG 索引/媒体缓存流转）、RAG 检索（pgvector + pg_trgm 混合召回）、数据源（PG/MySQL/Redis，只读通道有数据库级只读约束）、环境控制台（Docker/日志/进程守护）、自动任务（手动/每日/每周调度）、MCP 管理（含 STDIO 本地进程与 GitHub OAuth）、手机相册（独立仓库）。

**近期质量修复（2026-09-19/20，依据 PROJECT-ANALYSIS-2026-09-19.md）**：
- P1：中继缓存鉴权绕过、只读 SQL 边界（数据库级只读）、自动任务假成功
- P2：审批竞态、SSE 游标协议与回放缺口、单会话并发轮次、首次索引事务、文件生命周期同步补偿、登录后媒体令牌
- 工程：lint 全绿、路由级懒加载（主包 gzip 240KB→97.7KB）

## 文档索引

| 文档 | 内容 |
|------|------|
| [PROJECT-ANALYSIS-2026-09-19.md](PROJECT-ANALYSIS-2026-09-19.md) | **最新全面分析**（问题清单与推进顺序，P1/P2 已落地） |
| [PROGRESS-REVIEW-2026-09-06.md](PROGRESS-REVIEW-2026-09-06.md) | 历史进度评估（口径过时，保留作参考） |
| [nora-web/README.md](nora-web/README.md) | 前端技术栈、项目结构、业务流 |
| [nora-web/AGENTS.md](nora-web/AGENTS.md) | 前端开发规约 |
| [nora-api/README.md](nora-api/README.md) | 后端服务清单、端点、启动方式 |
| [nora-api/docs/architecture-v2.md](nora-api/docs/architecture-v2.md) | 微服务架构设计与 Phase 0–4 路线图（实施基准） |
| [nora-api/docs/agent-implementation-spec.md](nora-api/docs/agent-implementation-spec.md) | Agent 实施规格（ReAct 协议 + 高风险审批协议） |
| [phone-album-mcp/README.md](phone-album-mcp/README.md) | 手机相册 MCP（Android App + 中继部署） |
