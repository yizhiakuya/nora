# 数据源 · 连接与查询（2026-09-04）

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与目标

数据源让开发者连接数据库、浏览 Schema、执行查询，查询结果可沉淀为知识库或自动任务，并让 AI 读取表结构辅助生成 SQL。

**非目标**：真实 DB 驱动、JDBC 连接、SQL 执行引擎。`devData.ts` + `delay` 模拟。

## 2. 页面结构

```
app/data-sources/page.tsx
├── Header（“新建连接”按钮 → NewConnectionModal）
└── 双栏
    ├── ConnectionList（连接列表，选中态）
    └── 右栏
        ├── 连接信息条（名称/连接串/状态点/连接池 active/max）
        ├── Tab: Schema 浏览 / 查询控制台
        ├── SchemaBrowser（表列表 + 列定义 + 行数/大小）
        └── QueryConsole（SQL 输入 + 执行 + 历史 + 导出/存任务）
```

## 3. 关键交互

| 模块 | 行为 |
|------|------|
| 新建连接 | `NewConnectionModal` 选择 engine/host/port/database，成功后 `useConnections.add` 并 `addNotification` |
| 切换连接 | `selectedId` 本地 state，连接条与 Schema/Console 随 `selected.database` 联动 |
| Schema 浏览 | `SchemaBrowser` 展示 `DbTable[]`/`DbColumn[]`（primary/nullable/comment），来自 `devData.ts` |
| 查询控制台 | `QueryConsole` 输入 SQL → Mock 延迟执行 → 返回表格/错误；历史 `QueryHistory[]` 追加；支持导出 CSV toast、**保存为自动任务**（`useAutomations.addRule`） |
| AI 辅助 | 连接成功通知提示“AI 可读取 Schema 辅助生成 SQL”（联动知识库 `database` 来源） |

## 4. 状态与数据

| Hook/数据 | 说明 |
|-----------|------|
| `useConnections` (`hooks/useConnections.ts`) | `connections: DbConnection[]`，`persist` |
| `devData.ts` (`MOCK_CONNECTIONS`/`MOCK_TABLES`/`MOCK_HISTORY`) | 种子数据 + Schema 映射 |
| `useAutomations` | QueryConsole“存为自动任务”入口 |
| `types/index.ts` (`DbConnection`/`DbTable`/`DbColumn`/`QueryHistory`) | 领域类型 |

## 5. 非目标

- 真实 `pg`/`mysql2`/`redis` 连接、SQL 语法校验/执行计划

## 6. 验证

- 新建连接后左侧新增且自动选中，右侧连接条更新
- Schema 浏览切表可见列定义；查询控制台执行后出历史，导出/存任务可触达
- `pnpm --dir nora-web build` 正常
