# 复杂度审计报告（2026-09-16）

> 审计方式：codebase-memory 知识图谱（Quality Analysis：死代码 / 高扇入 / 高扇出 / 循环依赖）+ 源码扫描（超长方法、文件规模、测试分布）+ 抽样人工核验（图谱对 TSX 有误报，逐条验证）。
> 索引基线：`2e0f2e3`，4865 节点 / 16829 边。

## 一、规模速览

| 层 | 文件数 | 行数 |
|---|---|---|
| Java（7 服务 + common + api 模块） | 158 | ~23.8k |
| TS / TSX | 165 | ~18.7k |
| SQL 迁移 | 42 | ~0.6k |
| **源码合计** | **~365** | **~43k** |

后端按服务：**agent 14.0k（占 Java 59%）** > rag 2.4k > env 1.7k > datasource 1.6k > file 0.8k > automation 0.7k > gateway 0.1k。

## 二、复杂度热点

| 热点 | 规模 | 说明 |
|---|---|---|
| **ChatOrchestrationService.java** | **4,197 行 / 96 方法** | 单文件占全部 Java 的 18%，拆分方案见 [chat-orchestration-refactor-plan-2026-09-16.md](chat-orchestration-refactor-plan-2026-09-16.md) |
| └ `executeTool()` | 562 行，84 个出向调用 | 巨型工具分发器（全仓库扇出第一） |
| └ `toolsSpec()` | 456 行 | 11 个工具的 JSON Schema 全硬编码在一个方法里 |
| └ `streamUpstream()` / `streamUpstreamResponses()` | 162 / 236 行 | 两套上游 SSE 流式实现并存（openai / responses 协议） |
| McpServerService | 863 行 / 42 方法 | 次热点（连接池 + STDIO 进程树 + 注册表混合） |
| AgentController | 759 行 / 32 方法 | 次热点（会话 CRUD + SSE 转发 + 审批端点混合） |
| AgentThoughtBlock.tsx | 650 行 | 前端最大组件 |
| agentApi.ts / useChat.ts | 603 / 501 行 | |
| NetworkSettings.tsx | 11 个 hook 调用 | 前端状态最密集 |

**扇入 Top**（`ApiResponse.ok` 70、`BusinessException` 63、`requestJson` 38）都是工具/内核的正常复用，不是坏味道；真正的扇出压力全部集中在 ChatOrchestrationService 一条线上。

**分层边界**：services→common 222 条依赖，方向正确、无逆向依赖。

## 三、图谱质量审计

### 死代码（24 个候选，抽验后分两类）

- **真死**（已清理，见第四节）：
  - `nora-web/src/lib/next-shims/` 的 `navigation.ts` / `redirect.ts` / `usePathname.ts` / `Link.tsx` / `Image.tsx`——Next→Vite 迁移残留，全仓库零引用；
  - `ChatOrchestrationService.llmClient` 字段 + `clientFor(ResolvedLlm)` 方法——构建后从未使用；
  - `EmbeddingService.modelName()` / `dimensions()`——注释声称"surfaced in index stats"，实际索引统计直接读 `EmbeddingProperties`，两方法零调用（测试里的同名调用属于其他类的 record，已核实）。
- **误报**（图谱不追踪 JSX 引用，全部在用，勿动）：`handleSave` / `copyId` / `goUp` / `openUpload` / `PreviewSkeleton` 等 React 回调。

### 循环依赖（4 组，均为伪影或无害）

- 27 成员大环：横跨 agent 与 datasource 两个独立服务，类级互调物理上不可能，判定为图谱同名方法合并的伪影；
- `callTool ↔ callToolRich`：真实边是委托调用（`callTool` 内部调 `callToolRich`），无害；
- 其余两组（close/shutdown 生命周期、model 字段）均无害。
- **结论：无真实架构级循环。**

### 索引盲区（已修复）

env-service / env-api 的 Java 源码（4 个目录，~1.7k 行）曾被索引器整体排除。根因：codebase-memory 内置 skip-list 按**目录名**匹配（`env`/`venv`/`__pycache__` 等 Python 惯例），误伤 `com/nora/env` 包。gitignore 并未忽略它们。

**修复**：仓库根新增 `.cbmignore`，用取反规则 `!**/env/` 恢复（实测：取反生效且 `node_modules`/`.venv` 内的同名目录仍被上层规则挡住，无副作用）。重新索引后 env-service 源码进入图谱。

### env-service 补审（图谱修复后）

| 文件 | 行数 | 说明 |
|---|---|---|
| ProcessSupervisorService | 502 行 / 25 方法 | 进程守护（supervise 49 行） |
| ServiceController | 447 行 / 21 方法 | services() 80 行 |
| DockerClientService | 336 行 / 18 方法 | Docker API 封装 |
| ManagedSourceService | 210 行 | 纳管源注册表 |

结论：规模健康，无超 500 行的类、无超 90 行的方法，无需专项处理。

## 四、已执行的清理（2026-09-16）

| 项 | 动作 | 验证 |
|---|---|---|
| next-shims 死文件 ×5 | 删除 `navigation.ts` / `redirect.ts` / `usePathname.ts` / `Link.tsx` / `Image.tsx`（保留在用 `dynamic.tsx`） | 全仓库零引用；typecheck/lint/test 全绿 |
| 对应 alias | `vite.config.ts` / `tsconfig.json` 删 3 条（next/navigation、next/link、next/image），保留 next/dynamic、next-themes | 构建通过 |
| `ChatOrchestrationService` 死代码 | 删 `llmClient` 字段（含构造器初始化块）与 `clientFor()` 方法、`RestClient` import | 编译 + 测试 + E2E SSE 冒烟通过 |
| `EmbeddingService` 死代码 | 删 `modelName()` / `dimensions()`（索引统计实际直读 EmbeddingProperties） | 编译 + 测试通过 |
| `GitHubOAuthModal` lint 修复 | 轮询回调自引用 useCallback 变量（react-hooks/immutability）→ 改局部递归函数；顺手修掉既有质量门红项 | lint 全绿 |
| `.cbmignore` | 新增，恢复 env 目录索引 | 重新索引 4865 → 5079 节点（+214） |
| 文档同步 | `nora-web/AGENTS.md`、`nora-web/README.md`、`vite-migration-2026-09-04.md` 兼容层描述更新 | — |

## 五、总评与建议优先级

**规模中等、分层健康、复杂度高度集中**——真正的复杂度风险几乎全部压在 agent-service 聊天编排这一条主线上，且它恰好是产品核心链路。

1. ~~**高**：按拆分方案重构 `ChatOrchestrationService`~~ **已于 2026-09-17 完成**（四步拆分，facade 4,178 → 1,502 行，-64%；详见拆分方案文档的执行状态）；
2. 中：`AgentController` / `McpServerService` 按域拆分（会话 API / SSE 转发 / 审批端点；连接池 / STDIO 进程 / 注册表）——**待办**；
3. 低：`AgentThoughtBlock.tsx` 可拆子组件——**待办**。

**剩余死代码候选（未处理，待定）**：`nora-web/src/app/loading.tsx`（RouteLoading）与 `error.tsx`（GlobalRouteError）——Next.js 约定文件残留，Vite 路由未接线（App.tsx 无引用）。删除或接线由后续决定。

**已知误报（勿删）**：图谱死代码列表中的 React 回调（`handleSave` / `copyId` / `goUp` / `openUpload` / `askAi` / `toggleAll` / `openAdd` / `openCreate` / `handleQueryTable` / `PreviewSkeleton` 等）——图谱不追踪 JSX 引用，逐条人工核验全部在用。

测试现状：34 个 Java 测试 + 126 个前端用例（24 个文件），按项目约定为无断言冒烟（执行代码路径、不校验结果），行为正确性依赖 E2E 实测。
