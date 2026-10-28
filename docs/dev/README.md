# 开发文档

项目演进过程中的设计、评审与实施记录(按时间倒序)。

| 文档 | 内容 |
|---|---|
| [ptc-mode-design-2026-10-23.md](ptc-mode-design-2026-10-23.md) | PTC 编程式工具调用模式设计：统一权限入口、隔离 JS 编排、父子步骤、取消恢复与验收 |
| [media-auth-2026-10-22.md](media-auth-2026-10-22.md) | 本站媒体鉴权修复、URL 边界处理、真实图片与画廊验收及前端部署 |
| [agent-domain-splits-2026-10-22.md](agent-domain-splits-2026-10-22.md) | Agent 域体量收敛:工具展示层、工作区引导注入、检索阶段三处拆分与验证 |
| [question-steering-2026-10-18.md](question-steering-2026-10-18.md) | 提问期间插入请求唤醒、同轮再次提问、幂等与引用、实时状态恢复 |
| [deployment-2026-10-18.md](deployment-2026-10-18.md) | megumin 发布按需工具与流畅交互，含数据备份、29 项线上检查与真实模型工具复用 |
| [agent-tools-simplification-2026-10-17.md](agent-tools-simplification-2026-10-17.md) | 通用 Agent 按需工具、统一环境入口、契约减量与真实模型验收 |
| [uiux-fluidity-2026-10-17.md](uiux-fluidity-2026-10-17.md) | 流畅交互调研、首页层级、统一动效、手机导航焦点与搜索空态，含真实浏览器验收 |
| [session-deletion-2026-10-13.md](session-deletion-2026-10-13.md) | 会话删除、撤销、同步与详情竞态、失效路由修复及真实 HTTP 回归 |
| [chat-lifecycle-2026-10-13.md](chat-lifecycle-2026-10-13.md) | 发送防重、断线恢复、立即停止竞态修复与线上浏览器验收 |
| [deployment-2026-10-13.md](deployment-2026-10-13.md) | megumin 工作台与健壮性版本发布、数据备份、21 项真实服务验收及正式 HTTPS 入口检查 |
| [robustness-2026-10-13.md](robustness-2026-10-13.md) | 任务并发与同步竞态、请求头契约、成果保存防重和登记错误、真实服务验收 |
| [workbench-polish-2026-10-13.md](workbench-polish-2026-10-13.md) | 全工作台视觉与交互统一：顶栏响应式、通知键盘操作、输入区、消息操作、紧凑任务列表与多页面验收 |
| [button-interactions-2026-10-13.md](button-interactions-2026-10-13.md) | 统一按钮与常用控件、合并发送/停止、保留队列入口，含真实会话和窄屏验收 |
| [project-directory-picker-2026-10-13.md](project-directory-picker-2026-10-13.md) | 会话工作区可视化选择：目录浏览、磁盘、上级、筛选、确认保存及真实接口与浏览器验收 |
| [context-meter-ui-2026-10-13.md](context-meter-ui-2026-10-13.md) | 聊天上下文预算改为进度条、百分比与可点击明细，保留真实计量和窄屏适配 |
| [ui-redesign-2026-10-13.md](ui-redesign-2026-10-13.md) | 桌面工作台 UI 与交互重设计：全局视觉、首页、会话、资料、搜索与真实浏览器验收截图 |
| [general-agent-m1-2026-10-12.md](general-agent-m1-2026-10-12.md) | 通用 Agent M1 已落地：历史搜索、运行与待处理入口、环境范围、成果深链接及真实服务验收 |
| [nora-general-agent-product-design-2026-10-12.md](../research/nora-general-agent-product-design-2026-10-12.md) | 通用 Agent 产品化研究：Codex / Claude Code / OpenClaw 官方设计、Nora 现状、UI/UX、运行交互、能力路线与验收标准 |
| [deployment-2026-10-12.md](deployment-2026-10-12.md) | megumin 单应用正式部署、镜像版本、备份恢复检查与上线验收 |
| [code-maintainability-implementation-2026-10-12.md](code-maintainability-implementation-2026-10-12.md) | 九项建议落实、生产源码净减 224 行、真实服务及浏览器验收、剩余验证边界 |
| [acceptance.md](acceptance.md) | 真实服务验收统一入口、模型与隔离数据前置条件、浏览器回归路径 |
| [code-maintainability-review-2026-10-11.md](code-maintainability-review-2026-10-11.md) | 代码量、质量与可维护性评估：九项优化建议、实施顺序与验收边界 |
| [backend-simplification-2026-10-11.md](backend-simplification-2026-10-11.md) | 单应用改造、Spring 组件装配、代码减量及真实链路验收 |
| [code-quality-2026-10-11.md](code-quality-2026-10-11.md) | 代码清理、依赖取舍、数据库执行统一与质量检查 |
| [session-task-scope-2026-10-11.md](session-task-scope-2026-10-11.md) | 会话任务范围与环境输出修复 |
| [run-finalization-recovery-2026-10-11.md](run-finalization-recovery-2026-10-11.md) | 运行终态结算与恢复 |
| [agent-architecture-audit-2026-10-10.md](agent-architecture-audit-2026-10-10.md) | Agent 整体架构审查 |
| [agent-architecture-fixes-2026-10-10.md](agent-architecture-fixes-2026-10-10.md) | 架构整改及验收 |
| [system-agent-perspective-2026-10-10.md](system-agent-perspective-2026-10-10.md) | 宿主系统智能体视角 |
| [agent-context-visibility-2026-10-10.md](agent-context-visibility-2026-10-10.md) | 上下文与执行信息可见性 |
| [live-turn-recovery-2026-10-10.md](live-turn-recovery-2026-10-10.md) | 活跃会话工作过程恢复 |
| [chat-session-storage-2026-10-10.md](chat-session-storage-2026-10-10.md) | 会话存储和损坏数据处理 |
| [chat-image-paste-2026-10-10.md](chat-image-paste-2026-10-10.md) | 图片粘贴与预览 |
| [megumin-storage-2026-10-10.md](megumin-storage-2026-10-10.md) | 独立硬盘与 Docker 存储迁移 |
| [PRODUCT-DESIGN-REVIEW-2026-09-27.md](PRODUCT-DESIGN-REVIEW-2026-09-27.md) | 产品设计评审(B1–B7 修复依据) |
| [PROJECT-ANALYSIS-2026-09-26.md](PROJECT-ANALYSIS-2026-09-26.md) | 项目分析(F1–F5 修复依据) |
| [NORA-KNOWLEDGE-OPTIMIZATION-DIFY-2026-09-22.md](NORA-KNOWLEDGE-OPTIMIZATION-DIFY-2026-09-22.md) | 知识库优化方案(阶段 A/B/D:证据完整 / 资料库分组 / 结构分段 / 检索评测) |
| [NORA-PRODUCT-REFACTOR-PLAN-2026-09-20.md](NORA-PRODUCT-REFACTOR-PLAN-2026-09-20.md) | 产品改造方案(信息架构 / 业务场景 / 契约 / 验收矩阵) |
| [NORA-REFACTOR-IMPLEMENTATION-LOG.md](NORA-REFACTOR-IMPLEMENTATION-LOG.md) | 改造实施记录(M0–M5 完成项与验证证据、已知限制) |
| [NORA-AGENT-ARCHITECTURE-DESIGN-2026-09-20.md](NORA-AGENT-ARCHITECTURE-DESIGN-2026-09-20.md) | Agent 架构设计(执行事实可靠 / 参数统一 / 结果状态) |
| [NORA-AGENT-ACCEPTANCE-2026-09-20.md](NORA-AGENT-ACCEPTANCE-2026-09-20.md) | Agent 验收记录(三轮验收 T1–T3) |
| [DEEPSEEK-REFACTOR-HANDOFF.md](DEEPSEEK-REFACTOR-HANDOFF.md) | 早期重构交接笔记 |
| [PROJECT-ANALYSIS-2026-09-19.md](PROJECT-ANALYSIS-2026-09-19.md) | 全面分析(P1/P2 问题清单,已落地) |
| [PROGRESS-REVIEW-2026-09-06.md](PROGRESS-REVIEW-2026-09-06.md) | 历史进度评估(口径过时,保留作参考) |

> 服务级设计文档在 [nora-api/docs/](../../nora-api/docs/);开发规约与 AI 辅助配置在 [.claude/](../../.claude/)。
