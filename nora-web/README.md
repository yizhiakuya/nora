# Nora 前端

个人智能体的 Web 界面。所有业务域连接真实后端，不提供本地 Mock 开关。

## 技术栈

Vite 7 · React 18 · react-router-dom v7 · TypeScript 5 · Tailwind CSS 3 · Radix UI · Zustand 5 · Vitest 4 · Knip

## 开发与检查

在本目录执行，使用 pnpm：

| 命令 | 用途 |
|---|---|
| `pnpm install` | 安装依赖 |
| `pnpm dev` | 开发服务器，固定端口 3001 |
| `pnpm typecheck` | TypeScript 检查 |
| `pnpm lint` | ESLint 检查 |
| `pnpm deadcode` | Knip 检查未使用文件、导出和依赖 |
| `pnpm test` | Vitest 冒烟测试 |
| `pnpm build` | 生产构建，输出 dist/ |
| `pnpm preview` | 预览生产构建 |

CI 执行类型、Lint、死代码、测试和构建检查。行为正确性通过真实 HTTP 与浏览器验收。

## 代码结构

| 目录 | 职责 |
|---|---|
| `src/main.tsx` / `src/App.tsx` | React 入口、路由、ThemeProvider、懒加载与错误边界 |
| `src/app/` | 助手首页、聊天、资料、任务、数据源、环境、Agent 管理和应用设置页面 |
| `src/components/ui/` | Radix 基础控件与项目通用 UI |
| `src/components/{domain}/` | 各业务域组件 |
| `src/components/shared/` | Markdown 等共享组件 |
| `src/components/ThemeProvider.tsx` | 主题状态，业务组件直接引用 |
| `src/hooks/` | 业务状态与接口编排 |
| `src/lib/api/` | HTTP、SSE、聊天和 Agent 契约 |
| `src/lib/services/` | 文件、知识库、数据源、环境、自动任务和模型接口 |
| `src/types/` | 共享业务类型 |
| `docs/` | 设计与演进记录 |

组件懒加载使用 React `lazy` / `Suspense`。不保留 Next.js shim 或别名。

## 主要链路

- 聊天：引用资料 → SSE 工作过程 → 排队/插入新要求 → 交付文件；工作过程可恢复。
- 文件与知识库：上传 → 文件管理 → 解析与索引 → 检索 → 对话引用。聊天图片点击后居中放大。
- 数据源：连接 PostgreSQL、MySQL 或 Redis → 浏览结构 → 执行读写命令 → 查看结果与历史。SQL 批次展示最后一组结果，最多展示 200 行。
- 自动任务：手动/每日/每周执行 → 记录结果 → 通知。文件/异常触发尚未接通。
- Agent 管理：模型、技能、MCP、上下文、工具和运行偏好；模型支持 OpenAI、Anthropic、Ollama。
- 统一侧面板：浏览器与文件查看器共用标签页；超链接默认在内置浏览器打开，打开方式可配置。

## 接口与配置

开发时 Vite 将 `/api` 代理到 Nora 后端 `http://localhost:18080`。统一 HTTP 客户端解开
`{code,data,message}` 信封并处理错误；聊天 SSE 单独处理步骤、流事件和终态。

知识库检索和统计由 `ragService.ts` 访问 `/api/rag/search`、`/api/rag/index/stats`；
聊天引用随 Agent 的 sources 事件返回。环境变量由 `/api/chat/settings/env-vars` 管理。

实现以代码和 [AGENTS.md](AGENTS.md) 为准。旧设计记录中的 Mock、旧导航和框架选型不代表当前实现。

## 维护文档

- [前端开发规范](AGENTS.md)
- [业务接入参考](docs/api-integration-status.md)
- [代码质量清理与验证](../docs/dev/code-quality-2026-10-11.md)
- [Agent 架构审查与整改](../docs/dev/agent-architecture-audit-2026-10-10.md)
- [统一文件查看器](docs/unified-file-viewer-design.md)
- [开发记录索引](../docs/dev/README.md)
