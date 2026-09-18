# 文件系统工具设计深度分析（2026-09-18 夜）

> 范围：`manage_workspace` / `read_file` / `fetch_media` 三个文件系统工具。
> 数据源：schema_agent.agent_step 近 30 天（终态行口径）。

---

## 一、现状全景（30 天真实数据）

| 工具 | 调用 | 失败率 | 均耗时 | 定位 |
|---|---|---|---|---|
| manage_workspace | 118 | **17.8%** | 38ms | 工作区+整机文件（10 个 action） |
| read_file | 21 | **14.3%** | 211ms | 文件中心（用户上传，8 个 action） |
| fetch_media | 30 | 0% | 67.7s | 批量媒体（相册→工作区） |

**manage_workspace 的 action 分布**：

| action | 调用 | 失败率 | 备注 |
|---|---|---|---|
| read | 37 | **32.4%** | 失败大头 |
| list | 28 | 0% | |
| write | 19 | 15.8% | |
| edit | 4 | 0% | 新功能健康 |
| mkdir | 3 | 66.7% | 样本小但失败模式明确 |
| import | 3 | 33.3% | |
| move/copy/append | 4 | 0% | |

---

## 二、失败画像（逐条核实过的根因）

### 问题 1：`filename` 方言**修复后仍有残留**（最顽固）

时间线显示：`filename` 别名修复（19:49 部署）前的 12 次失败是历史存量；
但注意**失败的分布**——37 次 read 里 12 次失败（32.4%），其中 9 次是 `filename`。
**这说明「模型对读文件的第一直觉就是 filename」是系统性的**，不是偶发：
- 其他模型/其他轮次还会继续写 filename（方言兼容层已兜住，但**schema 本身在对抗直觉**）；
- 更彻底的做法是**schema 层面接受**：把参数名从 `path` 改为更中性的……但 `path` 是绝对路径语义，
  `filename` 是文件名语义，两者不同。**保持 path 为主名 + filename/file 别名（已做）是正确取舍**。

### 问题 2：read_file 与 manage_workspace 的**选择成本**（结构性问题）

**证据**：7 个会话同时使用两个工具（rf 2 + mw 8 这种模式）；read_file 的 3 次失败全是
`id` 写成了路径（`{"action":"read","id":"MEMORY.md"}`）——**模型把文件中心当工作区用**：
- read_file 要数字 id（先 list 拿）；manage_workspace 要路径。**同为「读文件」，寻址方式不同**；
- 模型在会话中记住的是「read 文件」的意图，选哪个工具靠上下文猜。

**这是当前文件系统工具设计的最大摩擦点**。两个工具的存在有明确边界
（文件中心=用户上传+可索引；工作区=agent 记忆+整机），但对模型而言都是「读一个文件」。

### 问题 3：import 的目录语义（`path: "imports/"` 被拒）

**证据**：`{"action":"import","url":"...","path":"imports/"}` → 「目标是目录,不能写入」。
模型的意图是「导入到 imports 目录」（目录语义），但 import 的 path 是**文件路径**语义。
- 同类问题：write 的 path 也是文件语义，模型写目录会被拒（合理），但 import 场景下
  「给目录 + 从 URL 推断文件名」是**更符合直觉的形态**。

### 问题 4：日记文件「读未来的日期」

**证据**：3 次「文件不存在」中 2 次是 `memory/2026-09-17.md`（当天还没写）。
模型看到日记清单（bootstrap 注入的 dailyNotes）后，想读「今天的」——但今天的日记还没创建。
- 这是**正常的空态**，但错误消息没给「今天的日记还没有——要现在建吗」的出路。

### 问题 5：`mkdir` 缺 path 的消息不够可操作

**证据**：`mkdir 需要 path 参数(要创建的目录路径)`——没有示例。其他 action 都补了示例，
mkdir 漏了（实测 3 次里 2 次失败）。

---

## 三、设计层面对照（业界 + 本项目经验）

### 3.1 核心矛盾：一个「文件系统」被切成三个工具

```
用户视角的文件：            agent 工具视角：
用户上传的文件 ────────→  read_file（数字 id 寻址）
Agent 工作区文件 ──────→  manage_workspace（路径寻址）
整机文件 ─────────────→  manage_workspace（绝对路径）
媒体批量拉取 ──────────→  fetch_media（folder 参数）
```

**问题**：前三个本质是「同一个文件系统的三个区域」，但用了**两种寻址方式**（id vs 路径）
和**两个工具名**。模型需要记住「哪个区域用哪个寻址」。

**业界对照**：
- Claude Code：只有一套路径寻址（Read/Write/Edit 都吃路径），无 id 概念——**统一寻址**是简化关键；
- 但 Nora 的文件中心 id 是真实需要（文件中心文件有元数据/索引状态，且重名文件多）。

### 3.2 可选方案对比

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| A. 维持现状 + 强化文案 | 两个工具描述继续互指 | 零风险 | 选择成本仍在（已证明文案不够） |
| B. 统一为一个 `files` 工具 | 一个工具，`scope` 参数区分（center/workspace/machine） | 模型只需选一次 | 大改，破坏既有调用/历史兼容 |
| C. **read_file 支持路径寻址**（推荐） | read_file 的 id 参数接受路径形式（自动路由到工作区/文件中心） | 模型写路径→可用；写 id→可用；**消除选择成本** | 需要跨服务查询（文件中心按名找） |
| D. 文件中心文件挂进工作区视图 | 文件中心目录映射为工作区路径（如 `@center/文件名`） | 一套寻址 | 需要文件系统级集成，风险大 |

### 3.3 推荐：C 的轻量版——「read_file 的 id 兼容路径 + 自动路由」

```
read_file action=read 时:
  id 是数字        → 文件中心（现状）
  id/path 是路径   → 若以 @center/ 开头 → 文件中心按名查找
                   → 否则 → 转 manage_workspace 的 readAny（工作区/整机）
```

进一步：**模型写 `{"action":"read","id":"MEMORY.md"}` 时，直接路由到工作区读**——
这正是模型想做的事（它把两个工具的心智模型合成了一个）。错误消失，行为正确。

---

## 四、打磨清单（按价值排序）

> **实施状态（2026-09-18 夜）**：1-4 全部完成，5 由 1 的路径路由自然消解。

| # | 项 | 修法 | 状态 |
|---|---|---|---|
| 1 | **read_file 的 read 接受路径**（跨工具路由） | `routeReadByPath`：非数字 target 时——`@center/名` 或纯文件名先按名查文件中心（命中即读）；含分隔符或未命中转 `execManageWorkspaceRead`（工作区/整机，含图片图像通道）。`parseArgs` 同步提取 path/filename 到 target；schema 描述已宣传 | ✅ E2E：`id="MEMORY.md"` → 读出工作区文件 |
| 2 | **import 的 path 是目录时自动补文件名** | `path` 以 `/` 结尾或 `isDirectoryAny` 为真 → `filename`/URL 推断名拼入 | ✅ E2E：`path="imports/"` → 存入 `imports/java.png` |
| 3 | **日记空态出路** | `readPath` 对 `memory/YYYY-MM-DD.md`：今天→「还没有创建,可用 append」；过去→「可用 list dir=memory 查看已有」 | ✅ E2E：可操作提示 |
| 4 | mkdir 错误消息补示例 | 一行文案 | ✅ |
| 5 | read_file 错误消息交叉指引 | 由 1 消解（不再有「id 必须是数字」错误） | ✅ |
| 6 | （观察项）fetch_media 的 folder 语义 | 维持现状（不带 photos/ 前缀也能工作），靠 AGENTS.md 约定 | — |

**不做**：不合并工具（B/D 方案）、不改 path 主参数名——先做路由层，季度复盘再看。

## 五、验收结果

1. ✅ 单元：`journalEmptyStateGivesActionableHint` / `isDirectoryAnyDetectsDirectories`（133 测试通过）；
2. ✅ E2E 四个真实失败场景全部变为成功或可操作提示：
   - `id="MEMORY.md"` → 读出工作区文件（自动路由）
   - `path="imports/"` import → 存入 `imports/<推断名>`
   - 读不存在的日记 → 可操作提示
   - mkdir 缺 path → 带示例错误
3. ✅ `tool-eval.sh` 回归 10/10。
