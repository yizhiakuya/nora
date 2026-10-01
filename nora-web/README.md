# Nora — 前端

Nora 个人 AI 助手的前端仓库。

> 后端 `nora-api` 已上线（8 个微服务），本仓库通过 `VITE_USE_BACKEND` 开关接入真实 API；
> 全部业务域已对接后端，Mock 仅作 `USE_BACKEND=false` 的本地回退。详见下方[「后端接入现状」](#后端接入现状)。

## 技术栈

Vite 7 · React 18 · react-router-dom v7 · TypeScript 5 · Tailwind CSS 3 · Radix UI · Zustand 5 · Vitest 4

## 快速开始

```bash
pnpm install        # 安装依赖（严禁 npm / npx）
pnpm dev            # 开发服务器（固定 3001 端口，HMR 即时生效）
```

## 常用命令

| 命令 | 用途 |
|------|------|
| `pnpm dev` | 开发服务器（3001，改代码即时热更新） |
| `pnpm build` | 生产构建（输出 `dist/`） |
| `pnpm preview` | 生产预览（需先 build） |
| `pnpm typecheck` | TypeScript 编译检查 |
| `pnpm lint` | ESLint 检查 |
| `pnpm test` | Vitest 单元测试 |

## 项目结构

```
nora-web/
├── index.html              # Vite 入口（含字体、favicon）
├── vite.config.ts          # 构建配置（@ 别名 + next/* 兼容层映射）
├── src/
│   ├── main.tsx            # ReactDOM.createRoot + BrowserRouter + ThemeProvider
│   ├── App.tsx             # 路由表（助手/资料/任务/设置四入口 + 兼容路由,全部 React.lazy 懒加载 + 重定向 + 404 兜底）
│   ├── app/                # 路由页面组件（仅胶水层，≤150 行）
│   │   ├── page.tsx        #   助手首页（输入需求/继续处理/最近成果）
│   │   ├── files/          #   资料（全部文件/长期知识/已保存成果三视图）
│   │   ├── tasks/          #   任务（正在处理/定期任务/执行记录）
│   │   ├── chat/           #   会话工作区
│   │   ├── knowledge/      #   长期知识（兼容页,复用 KnowledgeView）
│   │   ├── skills/         #   技能（兼容页,复用 SkillsView）
│   │   ├── mcp/            #   连接与工具（兼容页,复用 ConnectionsView）
│   │   ├── data-sources/   #   数据源（高级工具）
│   │   ├── environments/   #   环境控制台（高级工具）
│   │   ├── settings/       #   设置（含 我的偏好/连接与工具/技能）
│   │   └── login/          #   令牌登录页
│   ├── components/
│   │   ├── ui/             # 基础 UI 原语（Button/Input/Switch/Select/Tabs/Modal）
│   │   │   └── custom/     #   共享 UI（Modal/UploadModal/States）
│   │   ├── {domain}/       # 领域业务组件（chat/files/knowledge/settings/model…）
│   │   ├── layout/         # Sidebar/Header/NotificationBell/ThemeProvider
│   │   └── shared/         # Markdown 等跨域组件
│   ├── hooks/              # 业务逻辑 Hooks（Zustand store + persist）
│   ├── lib/
│   │   ├── api/            # HTTP 客户端 / SSE / 请求缓存（client.ts、sse.ts、agentApi.ts、chatApi.ts、requestCache.ts）
│   │   ├── services/       # 后端 API 契约层（filesApi / ragService / datasourcesApi / environmentApi / automationsApi / modelsApi）
│   │   ├── next-shims/     # Next.js 兼容残留（仅 dynamic.tsx，其余已删）
│   │   ├── knowledgeSourceMeta.ts  # 文档来源的展示元数据
│   │   └── utils.ts        # 通用工具
│   └── types/              # 全局 TypeScript 接口
├── docs/                   # 设计文档与验证记录
└── scripts/                # 工具脚本（apply-dark-variants.ps1）
```

## 核心业务流

| 流程 | 说明 |
|------|------|
| 文件 → 知识库 → 对话 | 上传文件 → 加入知识库索引 → 对话引用知识库片段回答 → 可保存对话产出回知识库 |
| 数据源 → 查询 → 沉淀 | 连接数据库 → SQL 查询 → 导出 CSV 或保存为自动任务 |
| 环境 → 诊断 → 修复 | 服务异常 → AI 诊断 → 创建修复任务 → 自动任务执行 → 通知 |
| 模型管理 | 设置 → 模型管理：接入 OpenAI 兼容 / Anthropic / Ollama 服务商（协议类型 + 端点 + 密钥），多模型切换默认 |

## 后端接入现状

开关在 `src/lib/api/client.ts`：`USE_BACKEND` 读取 `VITE_USE_BACKEND`（`.env.local` 当前为 `true`），
开发时 Vite 把 `/api` 代理到 gateway `http://localhost:18080`。所有请求经 `requestJson` 统一解开
`{code,data,message}` 信封，`code != 0` 直接抛错。

以 `ragService.ts` 为例，同一能力提供**同步 Mock**与**异步后端**两套函数：

| 能力 | Mock 回退（USE_BACKEND=false） | 后端实现（USE_BACKEND=true） |
|------|------------------------------|---------------------------|
| 检索 | `searchDocs(query, docs, topK)` | `searchDocsAsync(query, topK)` → `POST /api/rag/search` |
| 索引统计 | `computeIndexStats(docs)` | `fetchIndexStats()` → `GET /api/rag/index/stats` |
| 引用来源 | `generateCitations(query, docs, topK)` | `generateCitationsAsync(query, topK)` → `POST /api/rag/citations` |

其余域同样按「`lib/services/xxxApi.ts` + Hook 内 `USE_BACKEND` 分流」的模式接入。

| 域 | 状态 |
|----|------|
| 文件 / 知识库 / 对话 / 数据源 / 环境 / 自动任务 / 模型管理 | ✅ 已接入后端 |
| AI 能力（技能）/ MCP 管理 / 通知 / 工作区 / 媒体缓存 / 登录 | ✅ 已接入后端 |
| 环境变量（设置页） | ✅ 已接入后端（`/api/env-vars`） |

接入新域或改契约后，请同步更新 [AGENTS.md](AGENTS.md) 的「后端接入现状」表。

## 设计文档索引

| 文档 | 内容 |
|------|------|
| [docs/product-redesign.md](docs/product-redesign.md) | 产品重定位、信息架构、业务流程闭环 |
| [docs/home.md](docs/home.md) | 首页概览（快捷入口 / 最近文件 / 侧栏状态） |
| [docs/chat.md](docs/chat.md) | 对话（多会话 / 流式消息 / 引用来源） |
| [docs/data-sources.md](docs/data-sources.md) | 数据源（连接管理 / Schema 浏览 / 查询控制台） |
| [docs/environments.md](docs/environments.md) | 环境控制台（服务卡片 / 日志流 / AI 诊断） |
| [docs/automations.md](docs/automations.md) | 自动任务（触发条件 / 执行历史 / 通知联动） |
| [docs/model-settings.md](docs/model-settings.md) | 模型管理设计（Tab 布局 + 协议类型 + 弹窗接入） |
| [docs/rag-service.md](docs/rag-service.md) | RAG 服务层（后端 API 契约预留） |
| [docs/skills-center.md](docs/skills-center.md) | AI 能力中心业务闭环设计 |
| [docs/file-viewer.md](docs/file-viewer.md) | 文件查看（File Viewer）功能设计 |
| [docs/vite-migration-2026-09-04.md](docs/vite-migration-2026-09-04.md) | Next.js → Vite 迁移记录 |
| [docs/engineering-fixes-2026-09-03.md](docs/engineering-fixes-2026-09-03.md) | 工程修复验证记录 |
| [docs/dark-mode-2026-09-03.md](docs/dark-mode-2026-09-03.md) | 全站暗色模式收口验证 |

## 开发规约

详见 [AGENTS.md](AGENTS.md)。核心约束：

- **只用 pnpm**——npm/npx 会破坏 pnpm 结构的 node_modules
- **页面仅胶水**——`src/app/*/page.tsx` ≤150 行，业务在 `components/{domain}`，逻辑在 `hooks`
- **Mock 只作回退**——`src/lib/mockData.ts` / `devData.ts` / `knowledgeData.ts` 仅用于 `USE_BACKEND=false`
  或后端尚未提供的域，已接后端的域不得用 Mock 覆盖真实返回值
- **dev 固定 3001**——HMR 即时生效，改代码不刷新页面
- **路由集中**——`src/App.tsx` 统一注册，`/src/app` 下只放页面胶水组件
