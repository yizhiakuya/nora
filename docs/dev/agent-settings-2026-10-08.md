# Agent 配置页面

## 需求与边界

主导航新增 `/agent`，集中管理 Nora 的默认行为、模型、指令与记忆、技能、工具连接和运行配置。应用 `/settings` 只管理外观、资料索引、通知和隐私。不增加多 Agent 档案、未接通的开关或自定义输出 token 上限。

## 现有链路与最小方案

- 模型：`useModelProviders` → `modelsApi` → 模型服务商接口；聊天请求已经携带模型与渠道 ID。直接复用模型管理组件和 store。
- 权限与思考等级：现有 `useAgentSettings` 藏在 `useChat` 中，使用 `/chat/settings`。将同一 store 移到独立 hook，聊天和管理页共享；服务端保存成功才更新选项，失败展示原错误。移除聊天中思考等级的第二份本地状态和重复保存。
- 过程显示：复用 `usePreferences.reasoningSummary`，简洁/详细与聊天快捷选择同步。
- 成果偏好：复用 `UserPreferencesSettings` 和服务端偏好接口，后端每次用户任务装配上下文时消费。
- 指令与记忆：`AgentWorkspaceService.bootstrap()` 读取 `SOUL.md`、`AGENTS.md`、`USER.md`、`MEMORY.md`。页面列出这些真实文件和工作区路径，打开现有 `SideWorkspace` 文件查看器进行编辑，沿用哈希冲突保护和草稿提醒，不另造编辑器。修改应用到下一次用户任务装配的上下文；正在进行的模型调用保持当前快照。
- 技能、MCP、环境变量与网络：复用已有管理组件和接口。原生终端仍由服务端按 OS 注册，不提供 shell 选择。
- 导航：新入口和全局搜索指向 `/agent`；旧 `/settings` Agent 分组与 `/models`、`/skills`、`/mcp`、`/env-vars` 书签重定向，配置只有一个编辑入口。

## 验收

前端类型检查、Lint、126 项冒烟测试与生产构建通过。真实浏览器完成七个分组读取、旧链接与模型子标签迁移、默认选项与聊天双向同步、成果偏好保存/刷新、指令文件侧面打开和编辑/取消，以及 390px 窄屏与深色模式验收。配置页和分类导航复用 `no-scrollbar` 隐藏滚动条，实际滚动仍正常；手机成果偏好输入保持 16px。测试后还原临时配置与外观。

真实 API 验收：`scripts/e2e-agent-settings.py`，通过环境变量 `NORA_BASE_URL` / `NORA_AUTH_TOKEN` 指定服务与已有鉴权，只保存当前值、不改变用户配置。

部署时发现服务器旧 gateway jar 未包含仓库已有的 `/api/user-preferences` 路由，导致偏好 GET 404。重新构建并发布 gateway 与 web，Agent 后端保持当前镜像。13 个容器健康，模型服务商、原会话、运行配置与其余 11 个镜像校验一致；备份 `/opt/nora/backups/20261008-092122`，gateway 发布 `20261008-092122`，最终 web 发布 `20261008-092901`。
