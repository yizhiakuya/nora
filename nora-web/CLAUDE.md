# nora-web — 前端

React 18 + Vite(3001) + Tailwind + shadcn 风格 ui + Zustand persist。

## 关键链路

- `lib/api/client.ts` — 统一请求:envelope(`{code,data,message}`,code=0 成功)、默认 30s 超时(调用方显式传 signal 时以其为准)、`USE_BACKEND` 开关(VITE_USE_BACKEND=true 走真实后端)
- `lib/api/agentApi.ts` — SSE 解析(reasoning_delta 聚合为 s-reasoning-N step)
- `hooks/useModelProviders` — provider 唯一数据源(Zustand persist + 后端同步)
- `components/chat/AgentThoughtBlock.tsx` — 思考块(roundIndex 分组;think 行默认展开,用户收起后尊重用户)
- `components/settings/model/ReasoningLevelConfig.tsx` — per-model 推理等级配置
- `hooks/useSkills` — 技能唯一数据源(列表走 /api/skills 不带正文;详情按需 loadDetail 拉正文——渐进披露);后端模式 CRUD 乐观更新+失败回滚
- `components/settings/WorkspaceSettings.tsx` — 设置中心「工作区」:agent 文件系统私有空间(也是记忆载体:USER.md/MEMORY.md 自动注入;memory/ 日记按需读);文件树 + 编辑器 + 保存/删除,人工检查与修正 agent 记忆的入口
- `components/files/WorkspaceBrowser.tsx` — 文件页「Agent 工作区」Tab:同一工作区的文件树浏览 + 编辑器(agent 记忆的人工查看入口);范围切换在 `app/files/page.tsx`(我的文件 / Agent 工作区)
- `lib/services/workspaceApi.ts` — 工作区 API 接入层(stats/files/read/write/delete)

## 约定

- 新功能必须兼容 USE_BACKEND=false(本地 mock)与 true(真实后端)两条路;后端模式不造假数据,空态引导操作
- 后端模式错误要 humanize:错误文案 + hint + 技术详情(errorRaw 折叠)+ 会话 ID 可复制
- UI 文案中文;样式用 Tailwind + CSS 变量(bg-card/text-foreground/border 等),明暗两套都要看一眼

## 已知坑

- HMR 对重命名导出不可靠,报 "does not provide an export" 先整页 reload 再判断
- Zustand persist 的 store 改字段结构时注意兼容旧 localStorage 数据
- 链路追踪:`client.ts` 每页面会话生成 browserTraceId,随全部请求走 `X-Nora-Trace-Id`;全局错误经 `lib/errorReporter.ts` 上报 `POST /api/log/frontend`(10s 去重);500 错误消息带服务端 `[trace=…]`,可与后端日志交叉检索

## 测试

```bash
pnpm exec tsc --noEmit
pnpm exec vitest run   # 87 用例
```
