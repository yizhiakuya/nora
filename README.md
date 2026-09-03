# Nora 个人工作台

AI 驱动的个人文件管理与开发者工作台——纯前端（Frontend-Only）仓库。

## 技术栈

Next.js 14 (App Router) · React 18 · TypeScript 5 · Tailwind CSS 3 · Radix UI · Zustand 5 · Lucide React

## 快速开始

```bash
pnpm install        # 安装依赖（严禁 npm / npx）
pnpm dev -p 3001    # 开发服务器
```

## 常用命令

| 命令 | 用途 |
|------|------|
| `pnpm dev -p 3001` | 开发服务器（固定 3001 端口） |
| `pnpm build` | 生产构建（需先停 dev） |
| `pnpm typecheck` | TypeScript 编译检查 |
| `pnpm lint` | ESLint 检查 |
| `pnpm test` | Vitest 单元测试 |
| `pnpm exec next start -p 3002` | 生产预览（用完即停） |

## 项目结构

```
src/
├── app/              # Next.js 路由页面（仅胶水层）
├── components/
│   ├── ui/           # 基础 UI 原语
│   │   └── custom/   # 共享 UI：Modal, UploadModal, States
│   ├── {domain}/     # 领域业务组件（chat, files, skills, agents…）
│   ├── layout/       # Sidebar, Header, NotificationBell
│   └── shared/       # Markdown 等跨域组件
├── hooks/            # 业务逻辑 Hooks（useChat, useSelection, useSkills…）
├── lib/
│   ├── api/          # Mock API 层（延迟模拟、文件预览工厂）
│   ├── devData.ts    # 开发者场景数据（数据库连接/服务/日志/自动任务）
│   ├── knowledgeData.ts # 知识库 RAG 管线数据（文档/索引/图谱/清洗规则）
│   └── mockData.ts   # 静态种子数据（文件/技能）
└── types/            # 全局 TypeScript 接口
```

## 设计文档索引

| 文档 | 内容 |
|------|------|
| [docs/product-redesign.md](docs/product-redesign.md) | 产品重定位设计（个人文件管理 + 开发者工作台） |
| [docs/file-viewer.md](docs/file-viewer.md) | 文件查看（File Viewer）功能设计与实现 |
| [docs/engineering-fixes-2026-09-03.md](docs/engineering-fixes-2026-09-03.md) | 工程修复验证记录（选择态、a11y、Vitest ESM） |
| [docs/dark-mode-2026-09-03.md](docs/dark-mode-2026-09-03.md) | 全站暗色模式收口验证记录 |

## 开发规约

详见 [AGENTS.md](AGENTS.md)。核心约束：

- **只用 pnpm**——npm/npx 会破坏 pnpm 结构的 node_modules
- **页面仅胶水**——≤150 行，业务在 `components/{domain}`，逻辑在 `hooks`
- **数据全 Mock**——始终基于 `src/lib/mockData.ts` / `devData.ts` / `knowledgeData.ts`
- **dev 固定 3001**——生产预览 3002，用完即停
- **构建前停 dev**——dev 与 build 共写 `.next`
