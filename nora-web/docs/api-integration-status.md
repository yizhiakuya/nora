# 前端业务接入参考

从既有前端规范整理于 2026-10-15，未在此次文档整理中重新验收；下表用于定位已有能力，不作为验收通过的证据。新增领域或调整契约时同步更新相关行，当前行为以实际契约和真实服务验收为准。

开发约束见 [前端规范](../AGENTS.md)，运行说明见 [README](../README.md)。

| 域 | 契约文件 | 后端端点 | 状态 |
|---|---|---|---|
| 文件 | `filesApi.ts` | `/api/files/**` | ✅ 已接入（上传/文件夹/列表/删除/回收站/预览/下载/索引） |
| 知识库 RAG | `ragService.ts` | `/api/rag/**` | ✅ 已接入（文档/检索/统计/引用/详情/重命名/删除/批量删除/重建索引） |
| 对话 Agent | `agentApi.ts` / `chatApi.ts` / `sse.ts` | `/api/chat/**` | ✅ 已接入（SSE step/delta/done/approval/context_usage + 断线重连；首次恢复与过期游标使用完整 snapshot，后续增量续传，文本按 50ms 合并且控制事件立即刷新；内置斜杠控制命令、压缩排队、语义摘要与持久化检查点、原文回读、编辑失效，完整输入含缓存，生成中每秒估算、实测校准及历史恢复 + 并发冲突 409；运行中消息排队、插入、撤回编辑，实时与历史按插入位置分段；重启恢复过程与检索范围、手动继续，未执行与结果未知分别显示） |
| Agent 配置 | `/agent` + `useAgentSettings` / `useModelProviders` / `userPreferencesApi.ts` | `/api/chat/settings`、`/api/user-preferences` 与下列管理接口 | ✅ 默认模型、思考等级、权限、过程显示和成果偏好；模型/技能/工具/环境/网络共用已有管理能力；指令记忆复用侧面文件面板。权限与思考等级服务端保存成功才更新，聊天和管理页共享同一 store |
| 数据源 | `datasourcesApi.ts` | `/api/datasources/**` | ✅ 已接入（连接/Schema/查询；引擎 pg/mysql/redis） |
| 环境控制台 | `environmentApi.ts` | `/api/environment/**` | ✅ 已接入（容器/日志 SSE/进程守护）；observer 标注观察主机/平台/执行域，FILE 日志不推断服务健康，unknown 展示未探测且不计为已确认运行 |
| 自动任务 | `automationsApi.ts` | `/api/automations/**` | ✅ 已接入（规则 CRUD/执行；**file/error 触发条件未接通,UI 已标不可选**） |
| 模型 Provider | `modelsApi.ts` | `/api/models/**` | ✅ 已接入（CRUD/连通测试/草稿探测不落库；每模型上下文窗口与思考等级；输出额度遵循上游默认） |
| 技能 | `skillsApi` / `useSkills` | `/api/skills/**` | ✅ 已接入（列表不带正文/详情按需拉取） |
| MCP 管理 | `mcpApi` | `/api/mcp/**` | ✅ 已接入（含 STDIO/GitHub OAuth） |
| 环境变量 | `envVarsApi.ts` | `/api/chat/settings/env-vars` | ✅ 与 Agent 原生工具共用存储，添加/删除使用单项接口，密钥回读脱敏；命令自动注入，MCP 使用 `${KEY}` 引用 |
| 通知 | `notificationsApi.ts` / `useNotifications` | `/api/notifications/**` | ✅ 已接入（领域服务直接写 PostgreSQL + 前端轮询同步） |
| 工作区 | `workspaceApi.ts` | `/api/workspace/**` | ✅ 已接入（文件页内「Agent 工作区」浏览器） |
| 内置浏览器 | `browserApi.ts` / `useBrowser` / `useBrowserResults` | `/api/workspace/browser/:sessionId` | ✅ 真实 Chromium 画面、多标签、人工接管；网页任务表单与进度卡、服务端样本确认、结果选行/去重/补采、CSV 下载与验收、方法保存进技能库 |
| 照片成长相册 | 首页入口 + 同名 Agent 技能 | `/api/workspace/photo-albums` | ✅ 已接入（真实选片、独立 HTML、内容去重增量、文案与样式反馈、明确偏好继承；open_file 交付） |
| 媒体缓存 | `mediaCacheApi.ts` | `/api/media/**` | ✅ 已接入（缓存列表/删除/保存到文件中心/代理预览） |
| 登录 | `authApi.ts` / `auth.ts` | `/api/auth/**` | ✅ 已接入（令牌登录,缺省免登录；令牌保存失败展示错误、不跳转；SSE/img 走 `?token=`） |

列表数据以服务端为准，localStorage 只保留偏好和草稿。文件、知识库、数据源、模型、环境与通知读取失败时保留内存旧数据并显示错误和重试；通知写成功后更新列表。批量下载在传输前校验全部文件，模型探测拒绝畸形响应。相关验收见[规范修复记录](../../docs/dev/standards-remediation-2026-10-15.md)。
