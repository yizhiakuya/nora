# nora-web — 前端

React 18 + Vite(3001) + Tailwind + shadcn 风格 ui + Zustand persist。

## 关键链路

- `lib/api/client.ts` — 统一请求:envelope(`{code,data,message}`,code=0 成功)、默认 30s 超时(调用方显式传 signal 时以其为准)、`USE_BACKEND` 开关(VITE_USE_BACKEND=true 走真实后端)
- `lib/api/agentApi.ts` — SSE 解析(reasoning_delta 聚合为 s-reasoning-N step)
- `hooks/useModelProviders` — provider 唯一数据源(Zustand persist + 后端同步)
- `components/chat/AgentThoughtBlock.tsx` — 思考块(roundIndex 分组;think 行默认展开,用户收起后尊重用户)
- `components/settings/model/ReasoningLevelConfig.tsx` — per-model 推理等级配置

## 约定

- 新功能必须兼容 USE_BACKEND=false(本地 mock)与 true(真实后端)两条路;后端模式不造假数据,空态引导操作
- 后端模式错误要 humanize:错误文案 + hint + 技术详情(errorRaw 折叠)+ 会话 ID 可复制
- UI 文案中文;样式用 Tailwind + CSS 变量(bg-card/text-foreground/border 等),明暗两套都要看一眼

## 已知坑

- HMR 对重命名导出不可靠,报 "does not provide an export" 先整页 reload 再判断
- Zustand persist 的 store 改字段结构时注意兼容旧 localStorage 数据

## 测试

```bash
pnpm exec tsc --noEmit
pnpm exec vitest run   # 70 用例
```
