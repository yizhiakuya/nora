# Nora 个人工作台前端项目规约 (Frontend Agent Constraints)

## 📌 项目定位
本项目（`nora-web`）是 AI 驱动的**个人文件管理与开发者工作台**的前端仓库。
目标用户：个人用户（文件管理）+ 开发者（数据源查询、环境控制、自动任务）。
技术栈：Vite 7 (React SPA) · react-router-dom v7 · Tailwind CSS 3 · Radix UI · Zustand 5 · TypeScript 5 · Vitest 4。

> **后端已接入（2026-09 更新）**：本仓库不再"纯前端"。后端 `nora-api`（Spring Boot 3.3 微服务）已提供
> file / rag / agent / datasource / env / automation 六组 API，前端通过 `VITE_USE_BACKEND` 开关切换
> 真实后端与本地 Mock。详见下方[「后端接入现状」](#-后端接入现状)。

## 🤖 AI 智能体开发职责边界 (Agent Boundaries)

### ✅ AI 负责的范围 (In Scope)
1. **UI 页面搭建与还原**：将设计图或原型精确转换为高质量的 React 组件，保证 Tailwind 样式的极致还原。
2. **前端交互逻辑**：弹窗动画、状态流转（上传、加载、失败等）、Tab 切换、表格排序与复选等。
3. **前端工程化**：确保代码通过 TypeScript 编译、ESLint 检查与 Vitest 测试，页面无 Unhandled Runtime Error。
4. **API 契约层对接**：在 `src/lib/services/*Api.ts` 中按后端契约编写请求函数，并处理 loading / error / 空态。

### ❌ AI 不负责的范围 (Out of Scope)
1. **不在前端实现后端业务逻辑**：不在此项目中引入 Prisma、Supabase、PostgreSQL 或任何 ORM / 直连数据库。
2. **不绕过契约层直连**：组件与 Hook 不得直接 `fetch`，必须经由 `src/lib/api/client.ts` 的 `requestJson`
   （统一解开 `{code,data,message}` 信封、统一错误抛出）与 `src/lib/services/*Api.ts`。
3. **不在前端伪造业务结果**：后端返回真实数据时不要用 Mock 覆盖；Mock 只作为 `USE_BACKEND=false` 的回退路径。
4. **不改动后端仓库**：后端代码在 `../nora-api`，需要改接口时提出契约需求而非在此处绕过。

## 🏗️ 前端架构与模块化规范 (Architecture & Modularization)
**核心原则：高内聚、低耦合，严禁出现数百行的臃肿组件。**

1. **业务解耦 (Mock 数据层)**：
   - Mock 数据按模块使用：`src/lib/mockData.ts`（文件/能力）、`src/lib/devData.ts`（数据源/环境/自动任务）、`src/lib/knowledgeData.ts`（知识库 RAG）。
   - 配合 `@faker-js/faker` 与 `src/lib/api/mockApi.ts` 模拟后端延迟(`setTimeout`)与分页机制。
   - **Mock 仅用于 `USE_BACKEND=false` 或后端尚未提供的域**，不得覆盖后端真实返回值。
2. **UI 模块化拆分 (UI Componentization)**：
   - 页面级文件 (`src/app/*/page.tsx`) **仅作为胶水层**，负责引入组件、使用 Hook 传递状态（建议 ≤150 行）。
   - 任何超过 100-150 行，或承担独立业务的 UI 区块，必须抽离为独立组件，存放在按领域划分的目录中（例如 `src/components/chat/ChatMessageItem.tsx`、`src/components/ui/custom/Modal.tsx`）。
3. **状态抽离 (Custom Hooks)**：
   - 页面中的复杂状态流转（批量选择、虚拟上传、聊天输入流）必须抽离为纯粹的 React Hooks 放入 `/src/hooks`。
   - 跨页面共享的业务状态用 Zustand store + `persist` 中间件（localStorage 持久化）。
   - **已接后端的域**：Hook 负责请求编排与乐观更新，仍需提供失败回退，避免后端不可用时白屏。
4. **类型安全 (Strict Typing)**：
   - 所有实体模型的数据结构必须提取至 `/src/types/index.ts` 中维护。
   - 后端 DTO 与前端类型出现字段差异时，**改前端类型去对齐后端契约**，并在类型定义处注明来源端点。

## 🔌 后端接入现状

- 开关：`src/lib/api/client.ts` 中的 `USE_BACKEND`（读取 `import.meta.env.VITE_USE_BACKEND`）。
- 开发环境：`.env.local` 当前为 `VITE_USE_BACKEND=true`；`vite.config.ts` 将 `/api` 代理到 `http://localhost:8080`（gateway）。
- 契约层：`src/lib/services/*Api.ts` + `src/lib/api/{agentApi,chatApi,sse}.ts`。

| 域 | 契约文件 | 后端端点 | 状态 |
|---|---|---|---|
| 文件 | `filesApi.ts` | `/api/files/**` | ✅ 已接入（上传/文件夹/列表/删除/回收站/预览/下载/索引） |
| 知识库 RAG | `ragService.ts` | `/api/rag/**` | ✅ 已接入（文档/检索/统计/引用/详情/重命名/删除/批量删除/重建索引） |
| 对话 Agent | `agentApi.ts` / `chatApi.ts` / `sse.ts` | `/api/chat/**` | ✅ 已接入（SSE step/delta/done/approval + 断线重连 + 并发冲突 409） |
| 数据源 | `datasourcesApi.ts` | `/api/datasources/**` | ✅ 已接入（连接/Schema/查询；引擎 pg/mysql/redis） |
| 环境控制台 | `environmentApi.ts` | `/api/environment/**` | ✅ 已接入（容器/日志 SSE/进程守护） |
| 自动任务 | `automationsApi.ts` | `/api/automations/**` | ✅ 已接入（规则 CRUD/执行；**file/error 触发条件未接通,UI 已标不可选**） |
| 模型 Provider | `modelsApi.ts` | `/api/models/**` | ✅ 已接入（CRUD/连通测试） |
| 技能 | `skillsApi` / `useSkills` | `/api/skills/**` | ✅ 已接入（列表不带正文/详情按需拉取） |
| MCP 管理 | `mcpApi` | `/api/mcp/**` | ✅ 已接入（含 STDIO/GitHub OAuth） |
| 通知 | `notificationsApi.ts` / `useNotifications` | `/api/notifications/**` | ✅ 已接入（Kafka 事件落库 + 前端轮询同步） |
| 工作区 | `workspaceApi.ts` | `/api/workspace/**` | ✅ 已接入（文件页内「Agent 工作区」浏览器） |
| 媒体缓存 | `mediaCacheApi.ts` | `/api/media/**` | ✅ 已接入（缓存列表/删除/保存到文件中心/代理预览） |
| 登录 | `auth.ts` | `/api/auth/**` | ✅ 已接入（令牌登录,缺省免登录；SSE/img 走 `?token=`） |

新增接入时遵循同一模式：在 `lib/services/` 建 `xxxApi.ts`，导出 async 函数并在内部 `requestJson`；
Hook 中按 `USE_BACKEND` 分流；保留同步 Mock 函数作为回退，**并在 JSDoc 中注明它是回退实现**。
**带令牌的媒体/下载入口必须用 `withAuthToken()`**（`<img>`/`window.open`/EventSource 无法带 header，2026-09-20 统一修复过一批遗漏）。

## 📁 目录约定 (Directory Structure)
- `/` 根目录：`index.html`（Vite 入口）、`vite.config.ts`（构建配置，含 `@` 别名与 `/api` 代理）、`src/main.tsx`（ReactDOM.createRoot + BrowserRouter）、`src/App.tsx`（路由表）
- `/src/app`: 路由页面组件 (Home, Chat, Files, Knowledge, Skills, Data-Sources, Environments, Automations, Settings)。由 `src/App.tsx` 统一注册到 react-router-dom。**禁止在此存放长段逻辑代码**。
- `/src/components/ui`: 基础 UI 原语（Button, Input, Switch, Select, Tabs, Modal）。
- `/src/components/{domain}`: 领域业务组件（如 `/chat`, `/files`, `/settings/model` 下的专属拆分组件）。
- `/src/components/layout`: Sidebar, Header, NotificationBell, ThemeProvider。
- `/src/components/shared`: Markdown 等跨域组件。
- `/src/hooks`: 前端业务逻辑钩子（Zustand store + persist 或纯状态机）。
- `/src/lib/api`: HTTP 客户端、SSE 客户端、Mock API（`client.ts` / `sse.ts` / `agentApi.ts` / `chatApi.ts` / `mockApi.ts`）。
- `/src/lib/services`: 后端 API 契约层（`filesApi.ts` / `ragService.ts` / `datasourcesApi.ts` / `environmentApi.ts` / `automationsApi.ts` / `modelsApi.ts`）。
- `/src/lib/next-shims`: Next.js 兼容残留（仅 `dynamic.tsx`；Link / Image / navigation / usePathname / redirect 已于 2026-09-16 删除——全仓库零引用）。
- `/src/lib`: 全局工具函数与静态 Mock 数据总线。
- `/src/types`: 全局 TypeScript 接口定义。

## 🔄 Next.js 兼容层 (next-shims)
本项目从 Next.js 14 迁移至 Vite 7 + react-router-dom v7。
`src/lib/next-shims/` 仅剩 `dynamic.tsx`（经 `next/dynamic` 别名被 Markdown.tsx 使用）与 `next-themes`（指向自写 ThemeProvider）。
其余 shim（Link / Image / navigation / usePathname / redirect）已于 2026-09-16 删除——业务组件全部使用 react-router-dom 原生 API，全仓库零引用。
新增组件一律直接使用 `react-router-dom` 的 `Link` / `useNavigate` / `useLocation`。

## 📦 包管理器规约 (Package Manager)
- 本项目**必须使用 `pnpm`** 管理依赖与运行脚本（如 `pnpm install`、`pnpm add -D xxx`、`pnpm dev`、`pnpm build`、`pnpm test`）。
- **禁止使用 `npm` / `npx`**：仓库使用 `pnpm-lock.yaml`，`node_modules` 为 pnpm 结构，混用 npm 会破坏依赖树。
- `pnpm-workspace.yaml` 中 `allowBuilds` 显式允许 esbuild / unrs-resolver 的构建脚本。

## 🛠️ 常用命令
| 命令 | 用途 |
|------|------|
| `pnpm dev` | 开发服务器（固定 3001 端口，HMR 即时生效） |
| `pnpm build` | 生产构建（输出 `dist/`） |
| `pnpm preview` | 生产预览（需先 build） |
| `pnpm typecheck` | TypeScript 编译检查（`tsc --noEmit`） |
| `pnpm lint` | ESLint 检查 |
| `pnpm test` | Vitest 单元测试 |

## 🎯 协作原则
以**可运行、可验证、与后端契约一致**为优先，而非仅视觉完整：

1. 改动涉及接口时，先确认后端契约（字段名、信封、错误码），再写前端。
2. 提交前跑完质量门：`pnpm typecheck && pnpm lint && pnpm test`，必要时补 `pnpm build`。
3. 保持模块化：页面只做胶水，业务进组件，逻辑进 Hook，类型进 `src/types`。
4. 文档随代码更新：接入新域或改契约后，同步更新本文件的「后端接入现状」表。

5. **不要以次充好**：缺依赖就安装依赖（pnpm add），不要手写临时替代品、不要降级实现、不要 mock 绕过。标准组件用标准库（如 radix-ui），与项目既有技术栈保持一致。

6. **单测不写断言**（2026-09-12 用户明确要求）：前端单元测试仅作冒烟——执行渲染/交互路径、不校验结果（抛异常即失败）；行为正确性由 E2E 实测验证。写测试时不要加 `expect(...)` 断言；改动相关文件时顺手移除已有断言。

7. **不主动用子代理**（2026-09-12 用户明确要求）：不要自行派发 subagent 分担任务，自己顺序执行；仅当用户明确要求时才用。
