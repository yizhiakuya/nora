# Nora 个人工作台前端项目规约 (Frontend Agent Constraints)

## 📌 项目定位
本项目（`nora-web`）是一个 AI 驱动的**个人文件管理与开发者工作台**的 **纯前端（Frontend-Only）** 仓库。
目标用户：个人用户（文件管理）+ 开发者（数据源查询、环境控制、自动任务）。
技术栈：Vite 7 (React SPA) · react-router-dom v7 · Tailwind CSS 3 · Radix UI · Zustand 5 · TypeScript 5 · Vitest 4。

## 🤖 AI 智能体开发职责边界 (Agent Boundaries)

当前正在协助开发的 AI 智能体（Codex / Claude 等）**仅负责前端开发与打磨**。

### ✅ AI 负责的范围 (In Scope)
1. **UI 页面搭建与还原**：将设计图或原型精确转换为高质量的 React 组件，保证 Tailwind 样式的极致还原。
2. **前端交互逻辑**：编写丝滑的弹窗动画、状态流转（上传、加载、失败等）、Tab 切换、表格排序与复选等。
3. **前端工程化**：确保代码通过 TypeScript 编译与 ESLint 检查，页面无 Unhandled Runtime Error。

### ❌ AI 不负责的范围 (Out of Scope)
1. **不考虑真实后端的实现**：不要尝试使用 Prisma、Supabase、PostgreSQL 或任何 ORM 工具在此项目中创建真实的数据库连接。
2. **不编写后端业务接口**：不要在此项目中编写处理真实身份验证、真正的文件切片上传、大模型 SSE (Server-Sent Events) 对接。
   `src/lib/services/ragService.ts` 已按后端 API 契约分层（searchDocs / computeIndexStats / generateCitations），
   后端接入时只改这一个文件，调用方（知识库组件 / 对话页）零改动。

## 🏗️ 前端架构与模块化规范 (Architecture & Modularization)
**核心原则：高内聚、低耦合，严禁出现数百行的臃肿组件。**

1. **业务解耦 (Mock 数据层)**：
   - 数据始终基于 Mock，开发和调优时按模块使用：`src/lib/mockData.ts`（文件/能力）、`src/lib/devData.ts`（数据源/环境/自动任务）、`src/lib/knowledgeData.ts`（知识库 RAG）。
   - 配合 `@faker-js/faker` 与 `src/lib/api/` (如 `mockApi.ts`) 模拟后端延迟(`setTimeout`)与分页机制。
2. **UI 模块化拆分 (UI Componentization)**：
   - 页面级文件 (`src/app/*/page.tsx`) **仅作为胶水层** (Controller)，负责引入组件、使用 Hook 传递状态。
   - 任何超过 100-150 行，或承担独立业务的 UI 区块，必须抽离为独立组件，存放在按领域划分的目录中（例如 `src/components/chat/ChatMessageItem.tsx`、`src/components/ui/custom/Modal.tsx`）。
3. **状态抽离 (Custom Hooks)**：
   - 页面中的复杂状态流转（如批量选择、虚拟上传、聊天输入流）必须抽离为纯粹的 React Hooks 放入 `/src/hooks`（如 `useSelection`, `useUpload`, `useChat`）。
   - 跨页面共享的业务状态用 Zustand store + `persist` 中间件（localStorage 持久化）。
4. **类型安全 (Strict Typing)**：
   - 所有实体模型的数据结构必须提取至 `/src/types/index.ts` 中维护。

## 📁 目录约定 (Directory Structure)
- `/` 根目录：`index.html`（Vite 入口）、`vite.config.ts`（构建配置，含 `@` 与 `next/*` 别名）、`src/main.tsx`（ReactDOM.createRoot + BrowserRouter）、`src/App.tsx`（路由表）
- `/src/app`: 路由页面组件 (Home, Chat, Files, Knowledge, Skills, Data-Sources, Environments, Automations, Settings)。由 `src/App.tsx` 统一注册到 react-router-dom。**禁止在此存放长段逻辑代码**。
- `/src/components/ui`: 基础 UI 原语（Button, Input, Switch, Select, Tabs, Modal）。
- `/src/components/{domain}`: 领域业务组件（如 `/chat`, `/files`, `/settings/model` 下的专属拆分组件）。
- `/src/components/layout`: Sidebar, Header, NotificationBell, ThemeProvider。
- `/src/components/shared`: Markdown 等跨域组件。
- `/src/hooks`: 前端业务逻辑钩子（Zustand store + persist 或纯状态机）。
- `/src/lib/api`: 延迟模拟与 Mock API 接口层。
- `/src/lib/services`: 后端 API 契约层（ragService.ts，后端接入时只改这里）。
- `/src/lib/next-shims`: Next.js → React Router 兼容层（Link / Image / dynamic / navigation / usePathname / redirect）。
- `/src/lib`: 全局工具函数与静态 Mock 数据总线。
- `/src/types`: 全局 TypeScript 接口定义。

## 🔄 Next.js 兼容层 (next-shims)
本项目从 Next.js 14 迁移至 Vite 7 + react-router-dom v7。
原有 `next/link` / `next/image` / `next/navigation` / `next/dynamic` / `next-themes` 的调用通过
`src/lib/next-shims/*` 映射到 React Router 等价物，业务组件无需改动即可工作。
新增组件时优先直接使用 `react-router-dom` 的 `Link` / `useNavigate` / `useLocation`，逐步替换 shim。

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
**「永远只做前端，把前端打磨到极致」**。任何新需求的引入，优先考虑如何在视觉和前端模块化架构上提供最完善的体验，而非实现其底层功能。遵守上述模块化设计模式，拒绝"屎山代码"。
