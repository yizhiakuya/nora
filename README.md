# Nora

AI 驱动的个人文件管理与开发者工作台。

## 目录结构

```
Nora/
├── nora-web/     # 前端 — Vite 7 + React 18 + Tailwind + Zustand
└── nora-api/     # 后端 — Spring Boot 3.3 (Java 21) + PostgreSQL/pgvector，设计文档见 nora-api/docs/
```

前后端独立，分别 `pnpm install` / `pnpm dev`；后端用 Maven 构建，Docker Compose 起基础设施。

## 当前进度（2026-09-06）

整体完成度约 **72%**：Phase 0（基础设施）与 Phase 1（RAG 链路）已完成，Phase 2（Agent 链路）收尾中，
Phase 3（数据源 + 自动任务）与 Phase 4（环境控制台）部分完成。

| 端 | 状态 |
|----|------|
| 后端 | 7 个微服务全部有实现，网关 6 条路由打通；缺口在通知 SSE |
| 前端 | 9 个页面全部可用，7 个域已接后端；AI 能力 / 设置 / 通知仍为本地 Zustand |

详细评估、模块状态与风险见 [PROGRESS-REVIEW-2026-09-06.md](PROGRESS-REVIEW-2026-09-06.md)。

## 文档索引

| 文档 | 内容 |
|------|------|
| [PROGRESS-REVIEW-2026-09-06.md](PROGRESS-REVIEW-2026-09-06.md) | 开发进度评估、待办与风险 |
| [nora-web/README.md](nora-web/README.md) | 前端技术栈、项目结构、业务流、设计文档索引 |
| [nora-web/AGENTS.md](nora-web/AGENTS.md) | 前端开发规约 + **后端接入现状表** |
| [nora-api/README.md](nora-api/README.md) | 后端服务清单、端点、启动方式、已知偏差 |
| [nora-api/docs/architecture-v2.md](nora-api/docs/architecture-v2.md) | 微服务架构设计与 Phase 0–4 路线图（实施基准） |
| [nora-api/docs/agent-implementation-spec.md](nora-api/docs/agent-implementation-spec.md) | Agent 实施规格（ReAct 协议 + 高风险审批协议） |
