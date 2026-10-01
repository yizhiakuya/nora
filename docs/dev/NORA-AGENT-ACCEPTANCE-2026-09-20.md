# Nora Agent 与工具设计验收记录

日期：2026-09-20  
最新结论（第三轮独立复验，`188ec67`）：**原 7 项中 5 项通过，F1/F4 仍有边界遗漏，暂不全部通过。上轮直接复现场景均已通过；剩余 3 个具体问题见文末第 9 节。**

首轮结论：暂不通过。以下第 1–6 节保留首轮证据，不代表修复后的现状。

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

位置：[ChatContextAssembler.java:168](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:168)、[taskAnchor:185](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:185)。

当前实现寻找被裁掉历史中的第一条用户消息，截取前 400 字，作为 system 消息注入，并写明“继续以它为准”。它没有合并用户后续的纠正、撤销或任务切换。

**实际复现：**第一条消息要求删除旧报告，随后用户改为保留全部报告、只做统计；历史足够长后，当前请求再次要求只统计、不删除。装配结果仍出现 system 指令：“最早的任务目标……继续以它为准：删除旧报告”。已确认的是错误的模型输入，不是模型已经执行了删除。

**影响：**长对话越过裁剪边界后，会重新激活过时任务，而且过时用户要求被放到更高优先级。

**修正方向：**维护随用户最新要求更新的简短任务状态，保留有效目标、限制和已完成事实。不能用最早消息替代当前任务，也不能把旧用户原话提升为必须遵循的 system 指令。

**复验标准：**用户修改或撤销任务后，即使相关历史被裁掉，装配上下文也不得恢复旧目标；最新限制必须保留。

### F2 · P2：MCP 的自动重放直接信任远端只读声明

位置：[McpServerService.java:331](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:331)、[declaredReadOnly:361](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:361)。

是否重做调用，直接取决于缓存中的 `readOnlyHint`。这条调用路径没有进一步确认本地是否信任该声明，与设计中“远端注解仅供参考，执行策略需要本地信任配置”不一致。

**实际复现：**本地测试工具先增加计数器，再返回 JSON-RPC 错误。同一调用在未声明只读时，请求一次并返回 `unknown`；仅将缓存声明改为只读后，请求两次，计数器增加两次，最终返回成功。没有配置额外本地授权或幂等保证。

**影响：**工具注解错误或缓存已过时时，有副作用的操作仍可能被自动执行两遍。这里验证的是调用异常后的重放分支，不是所有网络断连形态。

**修正方向：**默认保留未知结果；只有本地明确接受的只读能力，或具备可靠幂等保证的调用，才能自动重做。保持规则简单，不必建设复杂策略平台。

**复验标准：**远端自报只读不足以单独开启重放；受信任的只读操作可以重试；写操作返回异常后不会重复产生副作用。

### F3 · P2：全部下载失败被标为“部分成功”

位置：[ChatToolExecutor.java:1394](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolExecutor.java:1394)。

只要 `failed > 0` 就设置 `partial=true`，没有判断是否存在成功或可用的已存在文件。前面的清单失败分支只覆盖 `total == 0`，不能处理清单成功、文件全部下载失败的情况。

**实际复现：**通过真实 MCP 客户端获取包含一个文件的清单，该文件的本地 HTTP 下载返回错误。实际 `fetch_media` 步骤输出：`成功 0，跳过 0，失败 1，共 1`，但步骤状态为 `partial`，正文仍使用“已拉取到”和“部分成功”。

**修正方向：**全部失败应为失败；确有可用结果且还有失败项才是部分成功；已存在而跳过的文件应按明确规则计入可用结果。状态和文案从同一份统计派生。

**复验标准：**分别执行全部成功、全部失败、成功与失败混合、全部跳过四种情况，步骤状态和统计一致。

### F4 · P2：调用指纹仍然同时存在漏拦和误拦

位置：[ToolStepEmitter.java:135](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:135)、[normalizeArgs:527](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:527)。

工作区、MCP 等工具直接使用原始 JSON 作为指纹，包含展示用的 `description`，也受字段顺序影响。另一边，`manage_file` 只使用文件目标，不包含 action、改名或移动目的地。

**实际复现：**同一文件、同一结果、相同描述，第四次读取被阻断；仅每次改变描述，四次均执行成功。直接执行当前参数解析与归一化方法，文件 `12` 的 read、rename、move、delete 指纹均为 `12`。结合执行前的阻断判断，前一动作累计到阈值后会阻断后一种不同动作。

**修正方向：**指纹使用标准化的工具名、动作和业务参数；忽略展示字段及 JSON 排列，保留会改变操作含义的字段。兼容工具别名应归到同一个标准名称。

**复验标准：**改描述或字段顺序不能重置重复计数；同一文件的读取、改名和移动互不误判。

### F5 · P2：只比较结果头尾，会把真实变化误判为没有进展

位置：[ToolStepEmitter.java:266](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:266)。

超过 2000 字符的结果，只比较前后各 1000 字符和总长度。中间内容变化但长度不变时，结果指纹完全相同。

**实际复现：**连续更新一个 2206 字符的文件，每次只改变中间数字，随后通过真实工具读取。文件内容每轮都变化，第四轮仍被标为 `declined`。

**修正方向：**对完整结果计算稳定摘要，或使用工具提供的可靠内容版本/进度标识。不能把头尾采样相等解释为结果没有变化。

**复验标准：**头尾及长度相同、中段变化的连续读取能够继续；真正不变的结果仍能被识别。

### F6 · P2：截断提示给出的续读路径和起始行不可靠

位置：[AgentWorkspaceService.java:417](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/AgentWorkspaceService.java:417)。

初次读取按 200000 字符截断，提示却固定从第 501 行继续；路径也只保留文件名，丢失目录。

**实际复现：**`nested/long.txt` 共 1000 行，每行连同换行符为 1000 字符。初次读取覆盖 200 行，提示却要求读取 `long.txt`、`offset=501`。照抄提示会报文件不存在；即使手动补回目录，也会跳过第 201–500 行。直接调用正确路径的分段读取，1–10 行和下一起点 11 则正常。

**修正方向：**统一截断和续读的位置语义，返回真实下一位置和完整可解析路径；妥善处理在行中间截断及单行超长文件。

**复验标准：**嵌套路径、绝对路径、长行文件均能沿返回的游标继续读取，不遗漏内容。

### F7 · P2：本地参数解析失败也被报告为远端结果未知

位置：[McpServerService.java:323](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:323)。

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

临时探针目录：系统临时目录下的验收探针目录。

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

## 附：实施者修复记录（2026-09-20 晚，首轮验收后）

本节保留实施者的修复说明；独立复验结论见第 7 节。

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

## 7. 第二轮独立复验（2026-09-20）

验收提交：`3975fee`；修复提交：`41043c3`。开始时工作区干净。本轮重新编译执行后端测试，使用当前 `target/classes` 编译新的独立探针，并重跑前端门禁；没有修改业务代码。执行预算继续排除。

### 7.1 逐项结论

| 首轮问题 | 本轮结论 | 实际证据 |
|---|---|---|
| F1 历史任务锚点 | **部分修复** | 不再强制遵循最早目标，但有效目标和“不删除”限制仍会一起丢失，只剩“continue 1”。见 R1。 |
| F2 MCP 重放信任 | **通过** | 默认不重放；仅本地信任开启且远端声明只读时重试一次。四种组合均实测。 |
| F3 下载全部失败状态 | **通过** | 全失败=failed；全成功=completed；成功失败混合=partial；全跳过=completed。真实 HTTP 下载链路实测。 |
| F4 调用指纹 | **部分修复** | 内置工具换描述不再绕过；文件不同 action 已区分。但 MCP 业务字段被合并，嵌套参数换序仍绕过。见 R2/R3。 |
| F5 中段变化识别 | **通过** | 相同长度、相同头尾、中段逐次变化的文件，四次读取均 completed。 |
| F6 截断续读 | **部分修复** | 嵌套相对路径从真实第 201 行续读成功；单行超长文件也能补到末尾。但 Windows 反斜杠路径的提示不是合法 JSON。见 R4。 |
| F7 本地参数错误 | **通过** | 非法 JSON 的 tools/call 请求数为 0，返回 error=true、unknown=false。 |

### 7.2 剩余问题与修正方向

#### R1 · P2：最后一条旧用户消息不能替代有效任务状态（F1 未闭环）

位置：[ChatContextAssembler.java:199](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:199)。

当前实现把被裁历史中最后一条用户消息覆盖到 `latest`。如果这条消息只是“继续”，更早的任务目标和有效限制都会消失。

实际装配场景：最初要求删除报告，随后更正为“保留全部报告，只统计，不删除”，再经过 42 条短的往返消息，本轮用户只说“继续”。装配出的上下文既不含旧任务，也不含更正后的限制；锚点是 system 消息，内容仅为 `continue 1`。原来的“过时目标强制优先”问题已改善，但“长任务仍记得有效目标与限制”的复验标准未达到。

修正方向：保留随用户修订更新的简短任务状态，不要把任意最近一条消息当成完整任务。复验必须包含“明确目标/限制 → 多次继续 → 裁剪 → 继续”，不能只测最后一句恰好完整复述目标的情形。

#### R2 · P2：工作区参数规则被应用到全部 MCP 工具，造成误拦（F4 修复引入）

位置：[ToolStepEmitter.java:547](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:547)。

`canonicalArgs` 不接收工具名，却统一删除 `description`、合并 `filename/file/path`，并将 `save/fetch/download` 归为 `import`。这些规则只适用于已明确约定的内置工具；MCP 中的 description 可能是要更新的正文，path 与 filename 可以同时有独立含义，save 和 fetch 也可以是不同动作。

实际协议复现：同一 MCP 工具连续三次返回相同结果，第四次分别修改真实业务字段 description、filename，或把 action 从 save 改成 fetch。三种情况均被本地判为 declined，远端只收到前三次请求，新的操作没有发出。

此外，工作区自身也没有完全复用现有解析：`path=""` 时，执行器会回退到 filename；指纹却删除 filename。实测 a.txt 与 b.txt 的实际目标不同，指纹同为 `{"action":"read","path":""}`。

修正方向：按工具语义决定哪些字段可以删、哪些名称是别名；工作区直接复用现有路径解析。MCP 原始业务字段默认全部保留，只有本地明确作为展示元数据注入的字段才可忽略。

#### R3 · P2：只对最外层排序，lazy MCP 的嵌套参数仍能绕过重复检测（F4 未闭环）

位置：[ToolStepEmitter.java:568](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:568)。

外层键放入 TreeMap，但值仍直接调用 `JsonNode.toString()`。`manage_mcp action=call` 的真实参数在 arguments 对象内，其键顺序仍参与指纹。

实际协议复现：同一工具、同一组 `a=1,b=2,c=3` 参数、同样返回 `ok`，仅变换 arguments 内键的排列，四次均 completed，远端收到四次请求；第四次没有按既定重复规则阻断。

修正方向：对 JSON 对象递归规范化键顺序，保留数组顺序与业务值；对于明确接受 JSON 字符串的 arguments，先按真实执行语义解析。用现有 JSON 库序列化，不要手工拼键值文本。

#### R4 · P2：续读参数直接拼接路径，Windows 路径使 JSON 非法（F6 未闭环）

位置：[AgentWorkspaceService.java:428](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/AgentWorkspaceService.java:428)。

只有 originalPath 为空时才将反斜杠转成斜杠；正常传入 `C:\Users\…\long.txt` 时，会原样插进 JSON 字符串，未转义反斜杠。

实际通过 manage_workspace 读取临时目录中的长文件，原始调用参数由 JSON 库正确编码，读取成功；提取返回的续读示例后，用同一 ObjectMapper 解析却抛出 `JsonParseException`。因此“照提示继续读取”在常见 Windows 路径上仍不成立。

修正方向：将 action、path、offset 构造成对象，再由 JSON 库序列化。复验同时覆盖反斜杠绝对路径与嵌套相对路径。

### 7.3 本轮检查结果及边界

- 后端：`mvn -pl services/agent-service -am test -q` 成功；agent-service 135 项，0 失败、0 错误、0 跳过。
- 前端：typecheck、lint、126 项测试和 build 全部通过。CSS 无效声明与动态/静态导入重叠两条既有构建警告仍在。
- 新探针执行实际工作区工具、上下文装配、工具步骤与真实 MCP HTTP 客户端；MCP 使用本机测试服务和内存数据库，媒体下载覆盖 200/404 响应。
- 本轮没有重跑真实模型对话或浏览器交互；结论针对上述修复路径，不代表所有 Agent 场景均验收通过。
- 没有沿用旧脚本硬编码的错误续读路径作为新证据；本轮从当前工具返回值提取续读参数再执行。也没有用“输出中不再包含旧目标”代替“正确目标仍被保留”的检查。

复现材料：系统临时目录下的复现材料目录 下的 `RoundTwoProbe.java`、`run.py` 和 `observations.txt`。运行 `rtk proxy python -X utf8 <该目录>/run.py` 可执行；它读取项目已编译类，因此改代码后须先重新编译。探针不带单测断言，观察值保存在文本中。

```text
CONTEXT_KEEP_PRESENT=false OLD_TASK_PRESENT=false
CONTEXT_ANCHOR role=system ... continue 1
RETRY trust=false hint=true requests=1 unknown=true error=true
RETRY trust=true hint=true requests=2 unknown=false error=false
INVALID_JSON requests=0 unknown=false error=true
MEDIA failed=failed success=completed mixed=partial skipped=completed
MIDDLE_CHANGES=[completed, completed, completed, completed]
MCP_BUSINESS_FIELD=description/filename/action -> 第四次 declined，请求数 3
LAZY_NESTED_ORDER=[completed, completed, completed, completed] requests=4
CONTINUE nested/long.txt offset=201 status=completed
CONTINUE Windows反斜杠路径 hint_parse_error=JsonParseException
```

下一轮只需针对 R1–R4 修正并复验，保留已经通过的四项。不需要重写 Agent，也不需要增加执行预算。

## 8. 实施者第三轮修复记录（2026-09-20 深夜，针对 R1–R4）

R1–R4 全部修复，第二轮探针（`nora-agent-reacceptance-20260920/run.py`，源码同步更新）复验通过：

| 项 | 修复 | 复验输出 |
|---|---|---|
| R1 | `taskAnchor` 跳过寒暄/续接类短消息（「继续/好的/ok/谢谢」等模式）——42 条 "continue" 不再冲掉真实任务；只取最近一条**有实质内容**的用户表述 | `CONTEXT_KEEP_PRESENT=true OLD_TASK_PRESENT=false`；锚点内容为 KEEP_MARK 更正后的限制（此前仅 "continue 1"） |
| R2 | `canonicalArgs` 按工具语义分层：**挂载 MCP 工具（mcp__*）参数全部保留**（description/filename/action 可能是远端业务字段）；内置工具才做展示剔除与别名归一；`path=""` 时与执行层同语义回退 filename | `MCP_BUSINESS_FIELD=description/filename/action` 三种改动均 4/4 发出（此前 description 第 4 次被误拦）；`EMPTY_PATH` a.txt/b.txt 指纹各不相同 |
| R3 | 递归规范化 JSON（嵌套对象同样按键排序，数组顺序保留） | `LAZY_NESTED_ORDER=[completed×3, declined]`——arguments 换序不再绕过，第 4 次正确阻断（此前 4 次都执行） |
| R4 | 续读提示路径统一转正斜杠（resolveAny 两种分隔符都接受），示例 JSON 始终可解析 | Windows 反斜杠绝对路径：`offset=201 status=completed`（此前 hint_parse_error=JsonParseException） |

回归：后端 135 测试 / 工具评测 9/9 通过。修复提交见 git log（`fix(agent): 第二轮复验 R1-R4`）。

## 9. 第三轮独立复验（2026-09-20，`188ec67`）

**结论：上轮直接复现场景全部修复；同一逻辑的边界补测仍有 3 个遗漏。** 本轮重新编译、执行实际类与本机 MCP HTTP 链路，未修改业务代码。执行预算排除。

### 9.1 已通过的场景

- 明确的任务更正之后连续输入 `continue`，裁剪后仍保留更正内容，不恢复旧目标。
- 挂载 MCP 工具改变 description、filename 或 action，新的业务调用均实际发出，不再被误拦。
- 工作区 `path=""` 配合非空 filename，不同目标的指纹已区分。
- lazy MCP 的 arguments **对象**仅改变键顺序时，第四次按重复规则阻断，远端收到 3 次请求。
- Windows 反斜杠绝对路径和嵌套相对路径的续读示例均能解析并从真实第 201 行继续；单行超长文件可以补读到末尾。
- 前轮通过的 MCP 本地信任开关、参数错误分类、媒体四种结果状态及中段内容变化检测，本轮回归均通过。

按首轮编号：F2/F3/F5/F6/F7 通过；F1/F4 部分修复。按第二轮编号：R4 通过，R1–R3 的直接案例通过但仍存在下面的同类边界。

### 9.2 剩余问题

#### T1 · P2：只保留最后一条实质消息，仍会丢失先前有效限制

位置：[ChatContextAssembler.java:203](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:203)。

实际场景：用户先要求“统计报告数量，全程只读，禁止删除或覆盖原文件”，随后补充“先处理 2026 目录，其余规则不变”；多轮继续后触发裁剪。最终上下文只保留第二条的处理范围，统计目标及只读限制均消失。第二条明确没有撤销原规则，不能替代第一条。补测“继续吧”也会被当成实质消息，覆盖任务信息。

本次的关键词过滤修好了旧例子，但没有解决“任务信息分布在多条消息中”的问题。建议保留当前有效目标、累计限制和明确撤销关系的简短状态；不要继续扩充“继续”的正则来代替任务状态。这里确认的是装配上下文丢失信息，未声称模型已执行越权操作。

复验重点：完整目标与限制 → 补充范围且声明原规则不变 → 历史裁剪 → 用户继续。目标、范围、限制应同时存在。

#### T2 · P2：路径别名回退仍与执行器不同，连续空值会误拦另一文件

位置：[ToolStepEmitter.java:573](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:573)，对照 [RiskClassifier.java:244](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/RiskClassifier.java:244)。

指纹选择 filename 时只检查字段存在；filename 存在但为空时，不会继续选择 file，随后又把两字段都删除。实际执行器却会连续跳过空 path 和空 filename，使用 file。

实际通过工具执行：前三次读取 `{"action":"read","path":"","filename":"","file":"alias-a.txt"}` 成功；第四次改为 alias-b.txt，虽然真实目标不同，却被标记 declined，未读取第二个文件。

修正方向：工作区指纹直接复用 `workspacePathOf` 的输出，不再另写近似的回退逻辑。验收覆盖缺失、null、空串、空白字符串，以及多个别名同时存在的优先级。

#### T3 · P2：arguments 为 JSON 字符串时，重复检测仍未按执行语义归一

位置：[ToolStepEmitter.java:589](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:589)，对照 [ChatToolExecutor.java:1099](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolExecutor.java:1099)。

当前递归排序处理对象与数组，但文本节点原样保留。执行器明确支持 arguments 传 JSON 字符串，并将其解析为对象再调用远端；所以两个语义相同的调用，在指纹层仍可能不同。

实际协议复现：arguments 用 JSON 字符串承载同一组 `a=1,b=2,c=3`，只变换字符串内部对象的键顺序，四次均 completed，远端收到 4 次实际请求。同样参数改用对象传递，则第四次正确阻断。

修正方向：仅针对 `manage_mcp action=call` 已约定的 arguments 字符串先解析，再做递归规范化；对象和字符串入口应得到相同的指纹。不要把其他工具的普通文本字段擅自解释成 JSON。

### 9.3 检查与证据

- 后端 135 项测试通过，0 失败、0 错误、0 跳过。
- 前端 typecheck、lint、126 项测试、build 通过；此前两条构建警告仍在。
- 验证覆盖实际上下文装配、实际文件读取、工具步骤和本机 MCP HTTP 请求计数；未运行真实模型完整对话或浏览器 E2E，不扩展为全项目验收结论。
- 本轮只更新本文件。临时探针及输出：系统临时目录下的复现材料目录 中的 `RoundTwoProbe.java`、`run.py`、`observations.txt`。运行前需重新编译项目，脚本通过实际输出复验，不含单测断言。

```text
旧场景：CONTEXT_KEEP_PRESENT=true OLD_TASK_PRESENT=false
补充范围后：goal=false limit=false scope=true
续接“继续吧”后：goal=false limit=false
MCP_BUSINESS_FIELD=description/filename/action：4 次均发出
空 path + 空 filename + file 改目标：[completed, completed, completed, declined]
LAZY_NESTED_ORDER（对象）：[completed, completed, completed, declined] requests=3
LAZY_STRING_ORDER（JSON 字符串）：[completed, completed, completed, completed] requests=4
Windows 路径续读：offset=201 status=completed
```

下一轮聚焦 T1–T3。T2/T3 可以在现有参数处理处收敛；T1 需要保留累积任务信息，继续替换“最后一条消息”的筛选规则无法覆盖其根因。

## 10. 实施者第四轮修复记录（2026-09-20 深夜，针对 T1–T3 + 同类边角）

T1–T3 全部修复，并补齐同类边角（动作别名/文件中心寻址），第三轮探针复验通过：

| 项 | 修复 | 复验输出 |
|---|---|---|
| T1 | `buildMessages` 不再用"最后一条/摘录"替代累计要求：**按原顺序保留全部用户消息**（先为全部用户消息计预算，超预算时明确报错而不是静默丢弃），剩余空间装最近的助手/工具过程；system 提示新增第 9 条说明"用户消息按原顺序保留、后续补充不自动取消先前限制" | `AMENDED_CONTEXT goal=true limit=true scope=true`（目标+限制+范围同时保留）；`继续吧` 后 `goal=true limit=true`（不被续接语覆盖） |
| T2 | 工作区指纹直接复用 `workspacePathOf` 的输出（与执行器/权限判定同一别名序，缺失/null/空串/空白统一回退 path→filename→file） | `ALIAS_EMPTY_STATUSES=[completed×4]`——第 4 次 alias-b.txt 正确读取到 BBB（此前被误拦 declined） |
| T3 | `manage_mcp action=call` 的 arguments 在**执行与指纹共用同一解释**（`ChatToolExecutor.mcpArguments`）：对象与 JSON 字符串、省略与空白统一解析为对象后再递归排序 | `LAZY_STRING_ORDER=[completed×3, declined]`——JSON 字符串换序第 4 次正确阻断（此前 4 次都发出）；对象入口同前 |
| 边角 1 | **全部内置工具**的 action 别名归一进指纹（add→create / invoke→call / download→import / pause→disable 等，与执行层同一别名表）——同一执行语义的拼写必须同指纹，否则换拼写即可绕过重复检测 | 单测冒烟（`AgentContextAndArgumentsSmokeTest`）+ 既有 139 项测试通过 |
| 边角 2 | `manage_file` 寻址归一（id→path→filename，与执行层 parseArgs 提取序完全一致；数字目标统一存 id、非数字统一存 path）——同一目标的不同寻址拼写同指纹，不同目标（a.txt/b.txt）不同指纹 | `EMPTY_PATH` a/b 指纹各异；`FILE_ACTION` 四动作指纹各异 |

回归：后端 139 测试 / 前端 126 测试 / tsc / 工具评测 9/9 全部通过。

新增冒烟测试 `AgentContextAndArgumentsSmokeTest`（4 项：上下文保留/超限明确拒绝/参数归一/MCP arguments 形态解析），
按项目约定不带断言、只执行路径；行为正确性由上述探针的真实工具/HTTP 路径复验。
