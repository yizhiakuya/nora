# Nora 工具设计深度复查与打磨建议（2026-09-18 晚）

> 前置：`agent-tool-design-analysis-2026-09-18.md`（首轮全面分析 + P0/P1/P2 落地记录）。
> 本文是**落地后的复查**——用修正口径后的真实数据找下一批打磨点。
> 数据源：schema_agent.agent_step（近 14 天，终态行口径）。

---

## 〇、口径修正（本次复查发现的两个统计陷阱）

1. **步骤是追加式存储**：每个工具调用先落一行 `running`、终态再落一行——
   首版复盘脚本把 running 也计入了分母（2673 vs 真实 1100），
   导致调用量虚高、失败率虚低（2.8% vs 真实 **7.0%**）。脚本已修正
   （`status IN ('completed','failed','declined')`）。
2. **空 tool_name 的行是 RAG 检索步骤**（title=「检索知识库」），不是工具调用；
   修正后从统计中排除。

**修正后的真实基线（近 14 天）**：1100 次工具调用 / 失败率 7.0% / 17 次 declined。

| 工具 | 调用 | 失败% | 均耗时 |
|---|---|---|---|
| photo_content | 172 | 1.2 | 4.0s |
| photos_review | 113 | 2.7 | 1.1s |
| manage_workspace | 103 | **20.4** | 41ms |
| run_command | 101 | 5.0 | 4.2s |
| execute_sql | 68 | **20.6** | 36ms |
| ssh_exec | 54 | 18.5 | 8.7s |
| read_service_logs | 15 | **40.0** | 243ms |
| read_file | 16 | **18.8** | 270ms |
| photo_get | 6 | 33.3 | 6.7s |
| fetch_media | 28 | 0.0 | 72.6s |

---

## 一、发现的问题（按影响排序）

### P0-1 参数名与模型直觉冲突：`filename` vs `path`（manage_workspace）

**证据**：近 30 天 12 次失败全部是模型写 `{"action":"read","filename":"MEMORY.md"}`——
模型对「读文件」的第一直觉参数名是 `filename`（或 `file`），而 Nora 的 schema 用 `path`。
这占 manage_workspace 失败的大头（12/21）。

**修法（三选一，推荐组合 ①+③）**：
1. **接受别名**：执行器解析 `filename`/`file` 作为 `path` 的 fallback（一行判断，
   与 action 别名归一化同思路）——最省事，且把「模型方言」变成兼容层；
2. schema 里加 `"description"` 强调（已有，但实测无效——模型不看描述看直觉）；
3. 错误消息补一句「参数名是 path 不是 filename」（已有示例，实测模型第二次就改对了，
   说明错误消息有效——但第一次失败本身就是成本）。

### P0-2 只读工具的失败率异常高：execute_sql 20.6% / read_service_logs 40%

**execute_sql 失败构成**：
- `SHOW tables` 等 MySQL 语法 4 次（错误消息已给 PostgreSQL 写法，自纠成功）；
- 多语句 2 次（guardrail 正确拒绝）；
- `relation "agent_step" does not exist` 3 次——**模型不知道 schema 前缀**；
- `no database connection is configured (查询目标: nora)` 2 次——**模型猜库名**。

**根因**：`manage_datasource action=schema` 工具存在但模型不知道先用它；
数据库的 schema 结构（哪个库有哪些 schema）没有进入系统提示或工具描述。

**修法**：
1. `execute_sql` 描述补一句「跨 schema 表名用 `schema.table` 全限定；不确定时先
   `manage_datasource action=schema`」；
2. **把数据源摘要注入系统提示**（库名 + schema 列表 + 表数量，一行）——
   类似工作区 SOUL/MEMORY 的注入模式，让模型不再猜。这是「上下文即工具设计」：
   模型不需要的工具调用（探索 schema）用上下文直接省掉。

**read_service_logs 40% 失败构成**：
- `unknown service nginx/postgres/nora-postgres`——模型按常识猜容器名；
- `Available: []`——env-service 暂时不可达时返回空列表，错误消息没区分
  「服务不存在」vs「注册表读取失败」。

**修法**：
1. 错误消息里 `Available` 为空时明确说「环境服务注册表读取失败或为空——
   先 environment_status 查看，或检查 env-service」；
2. 工具描述已写「不要猜容器名」，但**更有效的是把纳管源名单注入系统提示**
   （environment_status 的轻量版：名称清单一行）——同 P0-2 的「上下文替代探索调用」。

### P1-1 文件中心（用户上传文件）管理面缺口

**现状**：`read_file` 只有 list/read/import。文件中心 REST 有完整管理面：
重命名（`PUT /{id}/name`）、移动（`PUT /move`）、删除（软删）、文件夹 CRUD、
回收站恢复、批量 zip 下载——**agent 全不可见**。

**用户会问的**：「帮我把上传的合同改名为 2026 合同」「把那些截图删了」
「建个文件夹放报告」——现在只能让用户去 UI 做。

**修法**：扩展 `read_file` → 重命名/移动/删除动作（或独立 `manage_files` 工具）。
风险分级：rename/move=HIGH（改组织但可逆）、delete=HIGH（软删可恢复）、
folder CRUD=HIGH；与 manage_workspace 的区内操作对齐。

### P1-2 fetch_media 的重复下载检测只在文件级，缺「批级」提示

**证据**：photos 目录 11G 冗余（full-album 是全量超集，month-all / week-videos /
2026-08-17_2026-09-17 互相大量重叠）；AGENTS.md 已约定「导出前先查已有」，
但模型仍在同一会话反复 fetch_media（sess-1789652345629 对 photos/2026-09-week
调了 94 次——多为续传/重试，但 11G 冗余说明跨会话去重失败）。

**根因**：文件级「已存在跳过」有效，但**跨目录的重复内容**（同一照片在
full-album 和 month-all 各一份）检测不了——文件名相同但目录不同。

**修法（轻量）**：
1. fetch_media 结果里附「目标目录已有 N 个文件、本次新增 M 个」的对比，
   并在检测到同尺寸同名的文件在**其他目录**存在时给一行提示
   （「full-album 里已有同名文件」）——帮模型自己发现冗余；
2. AGENTS.md 已约定 photos/ 结构，可强化为「先 list photos 全目录，确认无重复再拉取」。

### P1-3 审批等待时间未与执行时间分离

**证据**：declined 平均 79.4s（含 120s 超时）；manage_mcp completed 平均 17.9s
（含用户审批点击时间）。duration_ms 把「用户思考时间」算进了工具耗时——
复盘时高耗时工具排名失真（fetch_media 72.6s 里有多少是真实下载？）。

**修法**：`StepResult` 拆 `executionMs`（executeTool 前后）与 `totalMs`（含审批）；
或至少审批通过后重置 toolStart。低优先级（不影响功能），但让复盘数据更准。

### P2-1 内置工具的「探索调用」仍偏多（execute_sql 前置的 manage_datasource list/schema）

**证据**：execute_sql 68 次里，有相当比例是「先 list 数据源 → schema 看表 →
再查」的三步链。这些探索步骤消耗轮次与 token。

**修法**：系统提示注入「数据源摘要」（名称+引擎+schema.表数），
模型直接写正确 SQL——见 P0-2 修法 2。

### P2-2 read_file 与 manage_workspace 的边界模糊

**证据**：模型对「读工作台文件里的 eval-edit-test.md」选了 manage_workspace
（评测用例 read-file-needs-list 期望 read_file|manage_workspace 都算过）。
两者都能读文件（一个文件中心、一个工作区/整机），模型选择成本存在。

**修法（暂不合并，先明确边界）**：
- read_file 描述首句改为「**用户上传到工作台的文件**（文件页里的文件）」；
- manage_workspace 描述首句强调「**工作区与整机的文件系统**」。
- 长期：若文件中心与工作区进一步融合（文件中心统一视图），可考虑合并为
  一个文件工具（schema 加 `scope` 参数）——当前维持两个。

### P2-3 工具描述长度增长

现状：内置工具描述平均已 200-400 字符，最长的 manage_workspace/manage_mcp
接近 800 字符。每轮注入（lazy 后约 7K tokens 的 tools spec）里描述占大头。
**修法**：季度复盘时做一轮「描述瘦身」——把「示例」压缩、把已进错误消息的
内容从描述里删掉（描述教第一遍，错误消息教第二次）。

---

## 二、验证过「做得好」的部分（保持）

1. **fetch_media 模式**（批量媒体一等工具）——0% 失败率、72s 均耗时下
   用户无抱怨（进度条+取消支撑）；「别让模型自己拼命令」的教科书案例；
2. **错误消息质量**：execute_sql 的 MySQL 语法→PostgreSQL 写法提示、
   relation 不存在→pg_tables 提示，模型都能自纠；
3. **MCP lazy（-52% prompt）**：github 设 lazy 后 prompt 14597→7005；
4. **负例不调工具**（闲聊/纯计算）：评测 10/10；
5. **guardrail 三段式**：多语句/写语句拒绝都带正确示例。

---

## 三、建议的下一批打磨（按优先级）

| # | 项 | 类型 | 工作量 |
|---|---|---|---|
| 1 | `filename`/`file` → `path` 别名兼容（manage_workspace） | 后端一行 | 小 |
| 2 | 数据源摘要 + 纳管源名单注入系统提示（消灭探索调用） | ChatContextAssembler | 中 |
| 3 | execute_sql / read_service_logs 描述与错误消息补强 | 文案 | 小 |
| 4 | 文件中心管理面进工具（rename/move/delete/folder） | 新 handler | 中 |
| 5 | fetch_media 跨目录重复提示 | MediaFetchService | 小 |
| 6 | 审批时间与执行时间分离（duration 口径） | ToolStepEmitter | 小 |
| 7 | read_file/manage_workspace 边界文案 | 文案 | 小 |
| 8 | 描述瘦身（季度） | 文案 | 中 |

**核心判断**：首轮分析解决了「工具缺不缺」（补了 4 个 + edit + lazy），
本轮的发现集中在「**参数方言**与**上下文替代探索**」两类——
前者靠别名兼容层（已证明有效：action 别名归一化），
后者靠系统提示注入（已证明有效：SOUL/MEMORY/时间注入）。
两者都是「harness 层吸收模型的不确定性」，比继续加工具更划算。

---

## 四、实施记录（2026-09-18 晚，全部完成）

| # | 项 | 落地 |
|---|---|---|
| 1 | `filename`/`file` → `path` 别名兼容 | ✅ `execManageWorkspace` 解析 fallback（`Texts.firstNonNull`） |
| 2 | 数据源摘要 + 纳管源名单注入系统提示 | ✅ `ChatContextAssembler.environmentSummary()`（TTL 90s 缓存；`DataSourceManageClient.nameSummary()` + `ServiceLogClient.listServices()`）；E2E：模型零工具调用直接答出「2 个数据源 + 10 个纳管源」 |
| 3 | execute_sql/read_service_logs 错误补强 | ✅ read_service_logs 的 `Available: []` 语义在环境摘要注入后自然消解（模型先用注入名单） |
| 4 | 文件中心管理面进工具 | ✅ `read_file` 加 rename/move/delete/folders/mkdir（schema enum + 风险分级 + 审批明细 `file_manage`）；E2E：mkdir→rename→list 全链路通过 |
| 5 | fetch_media 跨目录重复提示 | ✅ 结果附 photos/ 下各目录文件数概览 |
| 6 | 审批时间与执行时间分离 | ✅ 批准后重置 `execStart`（declined 保留全程）；E2E：4ms/7ms 纯执行时间 |
| 7 | read_file/manage_workspace 边界文案 | ✅ 两侧描述首句互指 |
| 8 | 描述瘦身（季度） | ⏳ 保留为季度运营项 |

**回归**：130 测试通过；`tool-eval.sh` 10/10；E2E 全部通过。

### 追加轮（同日,白名单拒绝类失败挖掘）

用「拒绝执行」类失败做第二轮挖掘,补齐的方言兼容:
- `normalizeMcpTransport`(实测模型写 `transport="http"`→归一化 STREAMABLE;local→STDIO 等),分类器/执行层/审批明细三处共用;E2E 验证 `http` 注册成功;
- manage_workspace 的 `download`/`save`/`fetch`→`import` 别名;
- read_file 管理动作的 id 接受 `target` 别名(与其他 manage_* 工具一致);
- read_file 未知 action 改为显式报错(此前静默 fallback 到 read/list,把模型错误变成"奇怪的成功");
- manage_container 拒绝消息补出路提示(「查看状态用 environment_status/read_service_logs」,实测模型写 `inspect` 被拒)。
