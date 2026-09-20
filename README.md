# Nora

个人 AI 助手：使用你的资料和已连接工具完成具体任务、交付成果，并持续处理重复工作。

## 目录结构

```
Nora/
├── nora-web/         # 前端 — Vite 7 + React 18 + Tailwind + Zustand（3001）
├── nora-api/         # 后端 — Spring Boot 3.3 (Java 21) 微服务，设计文档见 nora-api/docs/
└── phone-album-mcp/  # 手机相册 MCP（独立仓库：Android App + 中继服务）
```

前后端独立，分别 `pnpm install` / `pnpm dev`；后端用 Maven 构建，Docker Compose 起基础设施（PG+pgvector / Redis / Nacos / Kafka）。

## 当前状态（2026-09-20）

**能实际使用的单用户个人助手**。主导航按任务收敛为四入口：**助手 / 资料 / 任务 / 设置**（技术模块从主导航移到设置与高级工具，旧链接全部兼容）。

| 端 | 状态 |
|----|------|
| 后端 | 8 个微服务（gateway/file/rag/agent/datasource/env/automation/notification）；Kafka 事件通知；令牌登录（NORA_AUTH_TOKEN，缺省免登录）；对话运行持久化（chat_run）与定期任务日程契约 |
| 前端 | 助手首页（输入需求/继续处理/最近成果）+ 资料（文件/长期知识/已保存成果）+ 任务（正在处理/定期任务/执行记录）+ 设置（含连接与工具/技能/我的偏好）；USE_BACKEND 开关保留本地 mock 路径 |

**用户可见的核心闭环**：
- **资料处理 → 完整成果**：多选资料「交给助手」→ 引用注入真实内容 → 完整报告（画廊原生渲染）→「保存为文件」（工作区真实 Markdown，回读校验）/「保存到知识库」。
- **任务真实状态**：对话运行持久化（刷新/换页/进程重启可找回）；取消/中断/部分完成各有真实依据；自动结果全文可读。
- **定期任务**：真实日程（时间/星期/时区，服务端计算 nextRunAt 并可预览）；从成果一键创建；手动试跑不改计划点；错过宽限记录 missed_schedule 不冒充执行。
- **可信度**：删除/恢复/索引的生命周期同步带持久化重试与版本防乱序；「运行中/已启用/已完成/执行失败」文案与后台实际状态一致。

**近期修复记录**：见 [NORA-REFACTOR-IMPLEMENTATION-LOG.md](NORA-REFACTOR-IMPLEMENTATION-LOG.md)（M0–M5 各阶段验证证据）与 [PROJECT-ANALYSIS-2026-09-19.md](PROJECT-ANALYSIS-2026-09-19.md)（P1/P2 问题清单）。

## 文档索引

| 文档 | 内容 |
|------|------|
| [NORA-PRODUCT-REFACTOR-PLAN-2026-09-20.md](NORA-PRODUCT-REFACTOR-PLAN-2026-09-20.md) | **产品改造方案**（信息架构/业务场景/契约/验收矩阵） |
| [NORA-REFACTOR-IMPLEMENTATION-LOG.md](NORA-REFACTOR-IMPLEMENTATION-LOG.md) | **改造实施记录**（M0–M5 完成项与验证证据、已知限制） |
| [PROJECT-ANALYSIS-2026-09-19.md](PROJECT-ANALYSIS-2026-09-19.md) | 全面分析（问题清单与推进顺序，P1/P2 已落地） |
| [PROGRESS-REVIEW-2026-09-06.md](PROGRESS-REVIEW-2026-09-06.md) | 历史进度评估（口径过时，保留作参考） |
| [nora-web/README.md](nora-web/README.md) | 前端技术栈、项目结构、业务流 |
| [nora-web/AGENTS.md](nora-web/AGENTS.md) | 前端开发规约 |
| [nora-api/README.md](nora-api/README.md) | 后端服务清单、端点、启动方式 |
| [nora-api/docs/architecture-v2.md](nora-api/docs/architecture-v2.md) | 微服务架构设计与 Phase 0–4 路线图（实施基准） |
| [nora-api/docs/agent-implementation-spec.md](nora-api/docs/agent-implementation-spec.md) | Agent 实施规格（ReAct 协议 + 高风险审批协议） |
| [phone-album-mcp/README.md](phone-album-mcp/README.md) | 手机相册 MCP（Android App + 中继部署） |
