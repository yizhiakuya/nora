# Next.js → Vite 迁移记录 · 2026-09-04

> 状态：已完成 · 范围：构建工具从 Next.js 14 迁移到 Vite 7 + react-router-dom v7
>
> **后续更新（2026-09-16）**：兼容层清理——`next/link` / `next/image` / `next/navigation` 三个 shim 全仓库零引用，
> 已连同 vite/tsconfig 别名一并删除；仅保留 `next/dynamic`（Markdown.tsx 在用）与 `next-themes`（自写 ThemeProvider）。

## 1. 迁移动因

Next.js dev server 编译慢（每次改动需要整页重新编译），HMR 体验差。
Vite 冷启动 <300ms，HMR 毫秒级推送 ESM 模块，改代码不刷新页面、不丢状态。

## 2. 架构对照

| Next.js 14 | Vite 7 |
|------------|--------|
| App Router 文件路由 | `react-router-dom` v7，路由集中在 `src/App.tsx` |
| `next dev` (port 3001) | `vite --port 3001`（ready in ~200ms） |
| `next build` → `.next/` | `vite build` → `dist/` |
| `next/image` | shim → 原生 `<img>`（`src/lib/next-shims/Image.tsx`） |
| `next/link` | shim → `<Link>` from react-router-dom（`src/lib/next-shims/Link.tsx`） |
| `next/navigation` (useRouter/usePathname/redirect) | shim → react-router-dom（`src/lib/next-shims/navigation.ts` / `usePathname.ts` / `redirect.ts`） |
| `next/dynamic` | shim → `React.lazy + Suspense`（`src/lib/next-shims/dynamic.tsx`） |
| `next-themes` | 自写 ThemeProvider（同 API：useTheme → theme/setTheme/resolvedTheme，localStorage 持久化） |
| `next/font` (Inter) | index.html Google Fonts link |
| `eslint-config-next` | 标准 eslint-plugin-react + react-hooks |
| `.next/` 构建产物 | `dist/`（已加入 .gitignore） |

## 3. 兼容层设计

`src/lib/next-shims/` 保持 Next.js API 签名不变，内部映射到 React Router 等价物。
`vite.config.ts` 的 `resolve.alias` 把 `next/link` 等导入指向 shim 文件，业务组件零改动即可工作。

新增组件优先直接使用 `react-router-dom` 的 `Link` / `useNavigate` / `useLocation`，逐步替换 shim。

## 4. 文件变更

| 操作 | 文件 |
|------|------|
| 新增 | `index.html` / `vite.config.ts` / `src/main.tsx` / `src/App.tsx` / `src/lib/next-shims/*`（6 个 shim） |
| 删除 | `next.config.mjs` / `src/app/layout.tsx` / `src/app/fonts/` / `src/app/env-vars/page.tsx` / `src/app/models/page.tsx` |
| 修改 | `package.json`（scripts + 依赖）/ `tsconfig.json`（paths 别名）/ `.eslintrc.json` / `pnpm-workspace.yaml`（allowBuilds） |

## 5. 验证记录（2026-09-04）

| 检查 | 结果 |
|------|------|
| `pnpm typecheck` | 0 errors |
| `pnpm exec eslint src --ext .ts,.tsx` | 0 warnings/errors |
| `pnpm test` | 10 files / 45 passed |
| `pnpm build` | 2277 modules，dist 594KB (gzip 178KB) + Markdown 拆分 chunk |
| dev server | `http://localhost:3001` 200，所有路由 /settings /knowledge /chat /files 均 200 |
| HMR | 改代码即时生效，不刷新页面、不丢状态 |

## 6. 提交与回滚

```
23c70dc feat: migrate from Next.js to Vite 7 + react-router-dom v7
d24650f chore: update .gitignore for vite (dist/)
```

回滚：`git revert 23c70dc d24650f`（会同时回滚所有后续改动，不推荐）；
正确回滚方式：`git checkout 4f4bcbf -- . && pnpm install` 恢复到迁移前的 Next.js 版本。
