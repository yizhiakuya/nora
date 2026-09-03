# 工程修复验证记录 · 2026-09-03

> 状态：已验证 · 范围：工程风险收口（git 基线 / 依赖安装 / 选择态 / 配置 / 可访问性）

## 1. 修复范围

| # | 问题 | 修复前行为 | 修复后行为 |
|---|------|-----------|-----------|
| 1 | 仓库无版本控制 | 无历史、无回滚 | `git init` + 基线提交 `f6708e3` |
| 2 | `pnpm install` 退出码 1 | `[ERR_PNPM_IGNORED_BUILDS] Ignored build scripts: unrs-resolver@1.12.2` | `pnpm-workspace.yaml` 声明 `allowBuilds.unrs-resolver: true`，安装成功 |
| 3 | `useSelection` 陈旧选择 | 搜索过滤后 `selectedIds` 仍含不可见 id，"删除选中"会误删不可见文件 | 列表变化即剔除失效 id；新增回归用例 |
| 4 | Vitest 配置 ESM 警告 | `ESM syntax in a file loaded as CommonJS` | `vitest.config.ts` → `vitest.config.mjs`（ESM 路径解析） |
| 5 | 技能中心 Tab 不可键盘操作 | `div onClick`，无焦点、无语义 | 原生 `button` + `role="tablist"` / `role="tab"` / `aria-selected` |

## 2. 提交与产物

| 角色 | 内容 |
|------|------|
| 修改产物 | 工作区（`main` 分支，`0ee18c2`） |
| 基线快照 | `f6708e3`（原始源码 + `.gitignore` 新增 `dev.log` / `dev.pid` 忽略项） |
| 补丁文件 | `docs/engineering-fixes-2026-09-03.patch`（`git diff f6708e3 0ee18c2`） |
| 验证记录 | 本文件 |

## 3. 验证记录（全部实测于 2026-09-03）

| 命令 | 退出码 | 关键输出 |
|------|--------|----------|
| `pnpm install` | 0 | `✓ Lockfile passes supply-chain policies` / `Done in 516ms using pnpm v11.23.0` |
| `pnpm typecheck` | 0 | `tsc --noEmit` 无错误 |
| `pnpm lint` | 0 | `✔ No ESLint warnings or errors` |
| `pnpm test` | 0 | `Test Files 5 passed (5)` / `Tests 16 passed (16)`（原 15，新增 1 条回归用例） |
| `pnpm build` | 0 | `✓ Compiled successfully`，12 条路由全部静态生成，包体与基线一致 |

## 4. 行为对照

### useSelection

- **修复前**：选中文件 A、C 后搜索过滤到只剩 A，`selectedIds` 仍为 `[A, C]`；点击"删除选中"会把不可见的 C 一并移入回收站。
- **修复后**：过滤即剔除 C，删除只作用于可见选中项；恢复完整列表后 C 不会"复活"。
- **回归用例**：`src/hooks/useSelection.test.ts` →「列表过滤后自动剔除失效选中项」。

### pnpm install

- **修复前**：`[ERR_PNPM_IGNORED_BUILDS] Ignored build scripts: unrs-resolver@1.12.2`，退出码 1（根因：`pnpm-workspace.yaml` 中 `allowBuilds` 值为占位文本 `set this to true or false`）。
- **修复后**：退出码 0，unrs-resolver（eslint-config-next → eslint-import-resolver-typescript 的传递依赖）构建脚本被显式允许。

### 技能中心 Tab

- **修复前**：`div onClick`，键盘 Tab 键无法聚焦，无 ARIA 语义。
- **修复后**：原生 `button`（可聚焦、Enter/Space 触发）+ `role="tablist"` / `aria-label` / `role="tab"` / `aria-selected`。

### Vitest 配置

- **修复前**：`Your Vite config uses features that are unsupported by configLoader: 'native'`（ESM in CJS 警告）。
- **修复后**：`.mjs` 原生 ESM 配置（`fileURLToPath` 替代 `__dirname`），警告消失，16 用例全部通过。

## 5. 回滚

```powershell
git reset --hard f6708e3
```

该命令将工作区完全恢复到基线快照（含删除本记录与补丁文件）；被 git 忽略的运行时产物（`node_modules` / `.next` / `dev.log` / `dev.pid`）不受影响。
