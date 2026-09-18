# nora-web — 前端

React 18 + Vite(3001) + Tailwind + shadcn 风格 ui + Zustand persist。

## 关键链路

- `lib/api/client.ts` — 统一请求:envelope(`{code,data,message}`,code=0 成功)、默认 30s 超时(调用方显式传 signal 时以其为准)、`USE_BACKEND` 开关(VITE_USE_BACKEND=true 走真实后端)
- `lib/api/agentApi.ts` — SSE 解析(reasoning_delta 聚合为 s-reasoning-N step)
- `lib/chatRefs.ts` + `components/chat/RefChip.tsx` + `components/chat/ReferencePicker.tsx` — 输入框引用体系(2026-09-17):**三触发符分工**(对齐 Claude Code/Codex):`@` 文件中心 · `#` 知识库文档 · `/` 技能与 MCP 工具;内联菜单(↑↓/Enter/Esc、光标位置感知)、拖拽文件上传、📎 附件多选;引用以固定行格式序列化在消息尾部(`[引用文件] 名 (file_id=N)` / `[引用知识库] 名 (doc_id=N)` / `[引用技能] 名 (skill_id=N)` / `[引用MCP服务器] 名 (server_id=N)`)——随 content 持久化、历史可解析;后端 `MessageRefResolver` 解析后把真实内容注入上下文(排在检索命中前;MCP 按服务器 tool_policy 生成 eager/lazy 对应指引,前端只写中性文案)。改行格式必须两端同步(chatRefs.ts ↔ MessageRefResolver.java)
- `hooks/useModelProviders` — provider 唯一数据源(Zustand persist + 后端同步)
- `components/chat/AgentThoughtBlock.tsx` — 思考块(roundIndex 分组;think 行默认展开,用户收起后尊重用户);`ContextRow` 渲染注入上下文步骤(dsh 模式:form=instructions 显示文件清单+日记清单,**文件行可点击展开注入正文**;form=catalog 显示技能条目;折叠一行摘要,展开只读;未知 form 降级通用展示)。**已拆子组件(2026-09-17)**:工具行在 `ToolStepRow.tsx`(chip+参数/结果展开+MCP 图片灯箱/画廊),注入行在 `ContextStepRow.tsx`(含 ContextFileRow);主文件只留推理行+StepRow 分发+三个导出
- `components/settings/model/ReasoningLevelConfig.tsx` — per-model 推理等级配置
- `hooks/useSkills` — 技能唯一数据源(列表走 /api/skills 不带正文;详情按需 loadDetail 拉正文——渐进披露);后端模式 CRUD 乐观更新+失败回滚
- `components/files/WorkspaceBrowser.tsx` — 文件页内的「Agent 工作区」文件夹浏览器(文件系统一体化:普通文件夹形态,点击进入/面包屑导航/文件编辑器);`app/files/page.tsx` 持 `workspaceDir` 状态(null=根视图)
- `lib/services/workspaceApi.ts` — 工作区 API 接入层(stats/files/read/write/delete)

## 约定

- 新功能必须兼容 USE_BACKEND=false(本地 mock)与 true(真实后端)两条路;后端模式不造假数据,空态引导操作
- 后端模式错误要 humanize:错误文案 + hint + 技术详情(errorRaw 折叠)+ 会话 ID 可复制
- UI 文案中文;样式用 Tailwind + CSS 变量(bg-card/text-foreground/border 等),明暗两套都要看一眼

## 已知坑

- **错误结构化优先(异常处理系统 2026-09-12)**:`client.ts` 的 `requestJson` 非 2xx 时解析后端信封为 `ApiError`(带 category/errorCode/hint/retryable/traceId);`humanizeError` 优先按 `category` 映射文案(ApiError 入参),字符串启发式仅作兜底(网络异常/网关 HTML/旧接口)。新增错误 UI 时先传 Error 对象、不要先 `.message` 提取字符串
- HMR 对重命名导出不可靠,报 "does not provide an export" 先整页 reload 再判断
- Zustand persist 的 store 改字段结构时注意兼容旧 localStorage 数据
- 链路追踪:`client.ts` 每页面会话生成 browserTraceId,随全部请求走 `X-Nora-Trace-Id`;全局错误经 `lib/errorReporter.ts` 上报 `POST /api/log/frontend`(10s 去重);500 错误消息带服务端 `[trace=…]`,可与后端日志交叉检索

## 测试

```bash
pnpm exec tsc --noEmit
pnpm exec vitest run   # 87 用例
```
