# Nora Agent 与工具设计验收记录

日期：2026-09-20  
结论：**暂不通过。主干方向合理，部分能力已落地，但执行事实、循环检测和任务上下文仍有可复现缺陷。**  
范围：依据《Nora Agent 架构与工具设计建议》验收 Agent、工具和上下文相关改动，不涉及此前的产品业务改造。**执行预算按用户要求排除，不作为缺失项，也不建议补做。**

## 1. 验收方式与边界

- 起始提交：`6cdfcac`，主要实现提交：`a2ee19e`。
- 先核对知识图谱、调用关系与覆盖信息，再读取当前源码；覆盖信息提示 `metadata_changed` 的证据均回到源码核验。
- 运行工程检查，并使用本次编译的实际 Java 类执行隔离探针。文件读写只发生在临时目录。
- MCP 验证使用本机 HTTP 协议测试服务、真实 MCP 客户端和内存 H2 数据库；没有修改现有 MCP 配置，也没有操作真实外部账号。
- 未修改业务代码、未新增仓库单测。下述探针记录实际输出，不用断言或替代业务实现绕过问题。
- 验收期间 HEAD 前进至 `b1561bc`，该提交相对起始版本仅涉及前端会话代码；另有侧栏在途修改。这里的 Agent 后端证据仍适用，已跑的前端门禁只代表检查时的工作区，不能替这些后续修改背书。

## 2. 必须修正的问题

### F1 · P1：历史裁剪会把已经作废的目标重新提升为 system 指令

位置：[ChatContextAssembler.java:168](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:168)、[taskAnchor:185](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:185)。

当前实现寻找被裁掉历史中的第一条用户消息，截取前 400 字，作为 system 消息注入，并写明“继续以它为准”。它没有合并用户后续的纠正、撤销或任务切换。

**实际复现：**第一条消息要求删除旧报告，随后用户改为保留全部报告、只做统计；历史足够长后，当前请求再次要求只统计、不删除。装配结果仍出现 system 指令：“最早的任务目标……继续以它为准：删除旧报告”。已确认的是错误的模型输入，不是模型已经执行了删除。

**影响：**长对话越过裁剪边界后，会重新激活过时任务，而且过时用户要求被放到更高优先级。

**修正方向：**维护随用户最新要求更新的简短任务状态，保留有效目标、限制和已完成事实。不能用最早消息替代当前任务，也不能把旧用户原话提升为必须遵循的 system 指令。

**复验标准：**用户修改或撤销任务后，即使相关历史被裁掉，装配上下文也不得恢复旧目标；最新限制必须保留。

### F2 · P2：MCP 的自动重放直接信任远端只读声明

位置：[McpServerService.java:331](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:331)、[declaredReadOnly:361](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:361)。

是否重做调用，直接取决于缓存中的 `readOnlyHint`。这条调用路径没有进一步确认本地是否信任该声明，与设计中“远端注解仅供参考，执行策略需要本地信任配置”不一致。

**实际复现：**本地测试工具先增加计数器，再返回 JSON-RPC 错误。同一调用在未声明只读时，请求一次并返回 `unknown`；仅将缓存声明改为只读后，请求两次，计数器增加两次，最终返回成功。没有配置额外本地授权或幂等保证。

**影响：**工具注解错误或缓存已过时时，有副作用的操作仍可能被自动执行两遍。这里验证的是调用异常后的重放分支，不是所有网络断连形态。

**修正方向：**默认保留未知结果；只有本地明确接受的只读能力，或具备可靠幂等保证的调用，才能自动重做。保持规则简单，不必建设复杂策略平台。

**复验标准：**远端自报只读不足以单独开启重放；受信任的只读操作可以重试；写操作返回异常后不会重复产生副作用。

### F3 · P2：全部下载失败被标为“部分成功”

位置：[ChatToolExecutor.java:1394](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolExecutor.java:1394)。

只要 `failed > 0` 就设置 `partial=true`，没有判断是否存在成功或可用的已存在文件。前面的清单失败分支只覆盖 `total == 0`，不能处理清单成功、文件全部下载失败的情况。

**实际复现：**通过真实 MCP 客户端获取包含一个文件的清单，该文件的本地 HTTP 下载返回错误。实际 `fetch_media` 步骤输出：`成功 0，跳过 0，失败 1，共 1`，但步骤状态为 `partial`，正文仍使用“已拉取到”和“部分成功”。

**修正方向：**全部失败应为失败；确有可用结果且还有失败项才是部分成功；已存在而跳过的文件应按明确规则计入可用结果。状态和文案从同一份统计派生。

**复验标准：**分别执行全部成功、全部失败、成功与失败混合、全部跳过四种情况，步骤状态和统计一致。

### F4 · P2：调用指纹仍然同时存在漏拦和误拦

位置：[ToolStepEmitter.java:135](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:135)、[normalizeArgs:527](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:527)。

工作区、MCP 等工具直接使用原始 JSON 作为指纹，包含展示用的 `description`，也受字段顺序影响。另一边，`manage_file` 只使用文件目标，不包含 action、改名或移动目的地。

**实际复现：**同一文件、同一结果、相同描述，第四次读取被阻断；仅每次改变描述，四次均执行成功。直接执行当前参数解析与归一化方法，文件 `12` 的 read、rename、move、delete 指纹均为 `12`。结合执行前的阻断判断，前一动作累计到阈值后会阻断后一种不同动作。

**修正方向：**指纹使用标准化的工具名、动作和业务参数；忽略展示字段及 JSON 排列，保留会改变操作含义的字段。兼容工具别名应归到同一个标准名称。

**复验标准：**改描述或字段顺序不能重置重复计数；同一文件的读取、改名和移动互不误判。

### F5 · P2：只比较结果头尾，会把真实变化误判为没有进展

位置：[ToolStepEmitter.java:266](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:266)。

超过 2000 字符的结果，只比较前后各 1000 字符和总长度。中间内容变化但长度不变时，结果指纹完全相同。

**实际复现：**连续更新一个 2206 字符的文件，每次只改变中间数字，随后通过真实工具读取。文件内容每轮都变化，第四轮仍被标为 `declined`。

**修正方向：**对完整结果计算稳定摘要，或使用工具提供的可靠内容版本/进度标识。不能把头尾采样相等解释为结果没有变化。

**复验标准：**头尾及长度相同、中段变化的连续读取能够继续；真正不变的结果仍能被识别。

### F6 · P2：截断提示给出的续读路径和起始行不可靠

位置：[AgentWorkspaceService.java:417](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/AgentWorkspaceService.java:417)。

初次读取按 200000 字符截断，提示却固定从第 501 行继续；路径也只保留文件名，丢失目录。

**实际复现：**`nested/long.txt` 共 1000 行，每行连同换行符为 1000 字符。初次读取覆盖 200 行，提示却要求读取 `long.txt`、`offset=501`。照抄提示会报文件不存在；即使手动补回目录，也会跳过第 201–500 行。直接调用正确路径的分段读取，1–10 行和下一起点 11 则正常。

**修正方向：**统一截断和续读的位置语义，返回真实下一位置和完整可解析路径；妥善处理在行中间截断及单行超长文件。

**复验标准：**嵌套路径、绝对路径、长行文件均能沿返回的游标继续读取，不遗漏内容。

### F7 · P2：本地参数解析失败也被报告为远端结果未知

位置：[McpServerService.java:323](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:323)。

参数 JSON 解析与远端调用处在同一个异常处理分支。即使解析尚未完成、请求未发出，也会提示“该操作可能已在远端生效，不要直接重发”。

**实际复现：**输入非法 JSON，测试服务器记录 `tools/call` 请求数为 0，返回值却是 `unknown=true`。

**修正方向：**先完成本地解析与校验，再进入远端执行阶段。已知未发送的参数错误应报告失败并允许修正；仅无法确定远端执行结果的情况使用未知状态。

**复验标准：**非法参数不发出请求，返回可纠正的参数错误；发送后的不确定异常保留未知状态。

## 3. 对已实施能力的判定

| 能力 | 本次判定 |
|---|---|
| 工作区路径别名与权限分类 | 所测 `path`、`filename`、`file` 的区外写入均为 HIGH；相关共享解析已落地。这不等于全部工具已统一参数模型。 |
| MCP 异常后不盲目重放 | 未声明只读的实际协议调用通过；仍有 F2、F7。 |
| unknown / partial 结果状态 | 类型、执行映射和前端分支已增加；语义仍有 F3、F7，不能判定完成。 |
| 结果感知的循环检测 | 已有记录结果的机制，但 F4、F5 使核心目标尚未达成。 |
| MCP lazy 精确 schema | 已有按工具名读取缓存 schema 的入口；属于基础能力落地，不等同于完整的调用前 schema 校验。 |
| read_file 更名 manage_file | 新名称及旧入口兼容已落地；保留现有方向即可。 |
| 工作区分段读取 | 正确参数的分段读取实测通过；从截断结果继续读取因 F6 不通过。 |
| Skill 版本标识 | 读取结果附带 updatedAt 已落地；时间戳是标识，不能单独等同于可恢复的版本历史。 |
| 长任务上下文 | 最早用户消息锚点存在 F1，不能作为有效任务摘要验收。 |

实施记录另将记忆版本追溯、工具目录、`ERROR:` 内部判断退出和检索分层列为不做。这些属于**未实施或延期的设计项**，不是已完成成果；本次不因缺少这些结构调整而额外增加缺陷。执行预算明确不在验收范围。

## 4. 工程验证结果

| 检查 | 实际结果 |
|---|---|
| `mvn -pl services/agent-service -am test -q` | 成功；agent-service 的 11 份报告合计 135 项，失败/错误/跳过均为 0。 |
| `pnpm typecheck` | 通过。 |
| `pnpm lint` | 通过。 |
| `pnpm test --run` | 24 个测试文件、126 项通过。 |
| `pnpm build` | 成功；存在 CSS 无效声明及动态/静态导入重叠两条警告，不作为本次 Agent 阻断项。 |
| 实际类与本机 HTTP 探针 | 复现上述问题，同时确认部分正常路径。 |
| 后端健康检查 | `127.0.0.1:8083/actuator/health` 返回 UP；仅证明存活，不证明运行进程已加载本次构建。 |

本轮没有重跑带真实模型的完整用户对话，也没有进行浏览器界面验收；检查时本地 3001 前端服务未启动。未覆盖真实远端的所有断连方式、SDK 底层透明重试以及审批/取消完整链路。因此不把现有单测通过或实施日志中的 E2E 记录当成本轮完整验收通过的依据。

## 5. 修正顺序与复验

1. 先修 F1，避免长对话恢复已经撤销的任务。
2. 修 F2、F7、F3，确保是否执行、是否重做、实际完成多少三件事能够准确表达。
3. 修 F4、F5、F6，让重复检测和大结果回读真正可用。
4. 逐项运行上述复现场景，再用真实对话验证“更正任务后继续”“陌生 MCP 调用失败后恢复”“批量部分失败”“长文件连续读取”。

无需重写 Agent，也无需引入多 Agent。现有单主 Agent 加工具循环可以保留；此次阻断点集中在已有边界处理上。

## 6. 本机复现证据

临时探针目录：`C:/Users/24883/AppData/Local/Temp/nora-agent-acceptance-ehen37ee`。

- `probe.py` / `AcceptanceProbe.java`：参数别名、循环检测、上下文装配和文件续读。
- `probe-output.txt`：对应实际输出。
- `McpAcceptanceProbe.java`：真实 MCP 协议调用次数、本地参数失败、实际媒体下载全失败路径。
- `mcp-probe-output.txt`：对应实际输出。

关键观察值：

```text
LOOP_VARY_TITLE_false = [completed, completed, completed, declined]
LOOP_VARY_TITLE_true  = [completed, completed, completed, completed]
LOOP_REAL_MIDDLE_CHANGES = [completed, completed, completed, declined]
MCP_hint=false calls=1 unknown=true error=true
MCP_hint=true  calls=2 unknown=false error=false
MCP_invalid_json calls=0 unknown=true error=true
MEDIA_ALL_FAILED status=partial，成功 0，跳过 0，失败 1，共 1
```

临时目录可能被系统清理，交付给其他实施者时，以本文中的触发条件和复验标准为准，不依赖临时文件长期存在。

---

## 附：修复记录（2026-09-20 晚，验收后）

F1–F7 全部修复，逐项按本文复验标准重跑探针（`probe.py` / `McpAcceptanceProbe.java`，探针源码随本记录更新为当前实现）：

| 项 | 修复 | 探针复验（实际输出） |
|---|---|---|
| F1 | `taskAnchor` 取**最近**被裁用户消息（不是最早），措辞改为「仅作参考，后续消息优先」——不再把旧目标提升为 system 指令 | `TASK_ANCHOR=…以下是用户此前最近的表述,仅作参考…只统计,不要删除`（此前会输出"删除旧报告…以它为准"） |
| F2 | 重放需**本地信任配置**：新增 `nora.agent.mcp.trust-readonly-hints`（默认 false）——远端 readOnlyHint 单独不再开启重放 | `MCP_hint=true calls=1 unknown=true`（此前 calls=2 且成功） |
| F3 | fetch_media 状态从统计派生：可用=成功+跳过；全部失败=failed（含"全部 N 个失败"文案）；有可用+有失败=partial | `MEDIA_ALL_FAILED status=failed`（此前 partial） |
| F4 | 指纹=标准工具名+规范化参数（JSON 键排序、剔除 description、filename/file→path、动作方言归一）；read_file/manage_file 归一名 | `LOOP_VARY_TITLE_false=true=[…, declined]`（换描述不再绕过）；`FILE_FINGERPRINT_read/rename/move/delete` 四动作指纹各不相同；`CANON_DESC/CANON_ORDER` 与基准一致 |
| F5 | 结果指纹改全内容 SHA-256（前 16 hex）——中段变化不再误判 | `LOOP_REAL_MIDDLE_CHANGES=[completed×4]`（此前第 4 次 declined） |
| F6 | 截断提示给**完整原始路径**+**真实下一行号**（按截断点换行数计算） | `…已截断(约第 200 行处);继续读取:{"action": "read", "path": "nested/long.txt", "offset": 200}`（此前 `long.txt`+501） |
| F7 | 参数解析独立为阶段 0：解析失败=可纠正错误、请求不发出、不是 unknown | `MCP_invalid_json calls=0 unknown=false error=true`（此前 unknown=true） |

回归：后端 135 测试 / 前端 126 测试 / tsc / 工具评测 9/9 全部通过。

补充说明：
- F2 的信任开关是唯一新增配置项（默认最保守）。若将来确需对自建服务器开启只读重试，
  在 application.yml 设 `nora.agent.mcp.trust-readonly-hints: true` 即可。
- F4 的指纹实现同时服务「漏拦/误拦」两个方向：`canonicalArgs` 剔除展示差异（防绕过）、
  保留 action 与全部业务参数（防误判）。`normalizeArgs` 旧实现已删除。
