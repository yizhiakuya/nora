# 自动任务 · 触发与执行（2026-09-04）

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与目标

自动任务让重复工作自动化：定义“触发条件 → 执行动作”，到期执行并留下历史，可重试与通知闭环。

**非目标**：真实 cron/队列、后端执行器、定时触发。

## 2. 页面结构

```
app/automations/page.tsx
├── Header（“新建任务”按钮 → NewAutomationModal）
└── 标题 + Tab: 规则 / 执行历史
    ├── Tab=规则：AutomationList（规则卡片 + 启用开关 + 立即执行）
    └── Tab=执行历史：ExecutionHistory（时间/状态/耗时/重试）
```

- `role="tablist"` 原生 button，`w-fit` 胶囊容器选中态 `bg-white` 阴影

## 3. 关键交互

| 模块 | 行为 |
|------|------|
| 新建任务 | `NewAutomationModal` 输入 name/trigger/action → `useAutomations.addRule()` 追加到首位，`addNotification("新任务已创建")` |
| 启用/暂停 | `AutomationList` 卡片开关 → `toggleRule(id)`，`status` 在 `active`/`paused` 切换 |
| 立即执行 | 卡片“执行”→ `markRun(id)` 写 `lastRun="刚刚"`，推 `taskDone` 通知（耗时 1.2s Mock） |
| 执行历史 | `ExecutionHistory` 按 `lastRun` 展示，失败项可重试（触发通知与状态更新） |
| 外部创建 | 数据源 QueryConsole“保存为自动任务”、环境“AI 诊断→创建修复任务”均复用 `useAutomations.addRule` |

## 4. 状态层

| Hook | 职责 |
|------|------|
| `useAutomations` (`hooks/useAutomations.ts`) | `rules: AutomationRule[]` 唯一源；`addRule/toggleRule/markRun`；`persist` (`automations`)；副作用写 `useNotifications` |
| `lib/devData.ts` (`MOCK_AUTOMATIONS`) | 种子规则（触发/动作/状态） |
| `useNotifications` | 创建/执行完成通知（`taskDone` 去重与筛选） |

## 5. 非目标

- 真实调度（cron/消息队列）、执行沙箱、重试策略持久化

## 6. 验证

- 新建任务出现在规则首位；开关切换状态；执行后 `lastRun` 变“刚刚”且通知出现
- 数据源“存为自动任务”与环境“创建修复任务”均落到同一列表
- `pnpm --dir nora-web build` 正常
