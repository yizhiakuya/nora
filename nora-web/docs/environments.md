# 环境控制台 · 服务与日志（2026-09-04）

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与目标

环境控制台面向本地/测试环境的服务巡检：服务健康、日志流、AI 诊断并联动自动任务创建修复流程。

**非目标**：真实容器/进程探活、日志采集、LLM 诊断后端。

## 2. 页面结构

```
app/environments/page.tsx
├── Header（“本地开发环境”说明）
├── 标题 + Tab: 服务 / 日志
└── 内容
    ├── Tab=服务：ServiceCards + LogStream
    └── Tab=日志：LogStream（独占）
```

- `max-w-6xl mx-auto`，`animate-in` 入场；Tab 为 `role="tablist"`/`aria-selected` 原生 button

## 3. 关键交互

| 模块 | 行为 |
|------|------|
| ServiceCards | `ServiceCard[]` 来自 `devData.ts`，展示名称/状态(healthy/error/warning)/端口/重启/健康点；状态色与 Header 点样式一致 |
| LogStream | 追加式日志流（Mock 定时/静态），支持级别过滤与自动滚动 |
| AI 诊断 | 诊断按钮（若有）→ 提示并可“创建修复任务” → `useAutomations.addRule(trigger, action)` + `useNotifications` |
| 环境变量 | 同工作流在 `Settings → 环境变量`（`EnvVarsSettings`）编辑 `.env`，与控制台联动说明 |

## 4. 状态与数据

| 来源 | 说明 |
|------|------|
| `devData.ts` (`MOCK_SERVICES`/`MOCK_LOGS`) | 服务与日志种子 |
| `useAutomations` | 诊断→修复任务创建 |
| `useNotifications` | 服务异常/任务创建通知 |

## 5. 非目标

- 真实 Docker/K8s 探活、日志落盘、后端诊断接口

## 6. 验证

- 服务卡片状态点颜色正确；切“日志”Tab 仅 LogStream
- 诊断→创建任务后 `自动任务` 列表新增，通知出现
- `pnpm --dir nora-web build` 正常
