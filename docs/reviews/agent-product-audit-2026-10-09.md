# Nora Agent 能力、业务与交互评审

评审日期：2026-10-09。Nora 是单用户自部署的 Agent 工作台，覆盖对话、工具执行、浏览器、文件与知识库、数据库、环境管理及定时任务。本轮按“一人同时打开多个会话、浏览器标签页，并运行后台命令与自动任务”的实际使用规模判断。

后续实施：2026-10-09 已完成首批 1–7，并修正 11、19，真实服务验收通过。本文保留原审计时的发现；当前实现与验收结果见 [可靠性修正记录](../dev/agent-reliability-2026-10-09.md)。其余发现仍待处理。

结论：基础能力已经较齐，值得优先投入的是执行状态与数据的一致性、工具入口的一致性，以及跨页面使用的完整流程。继续添加工具之前，应先让“已停止、已完成、已排队、已保存、当前权限”这些承诺可靠成立。

这是只读评审：三个子代理分别检查执行与上下文、工具与后台任务、前端交互；主代理检查自动任务、资料处理和真实页面，再合并结论。没有修改业务代码、配置或部署，也没有发送新的模型请求。本文是本轮新增的交付文件。

## 范围与证据

- 对照当前工作区源码，而非把已有设计文档或历史报告当作实现事实。涉及 `agent-service`、`automation-service`、文件与 RAG 主链，以及前端对话、工作区、任务、资料、数据源、Agent 设置和导航。
- 已读 README、工程约定、主要依赖与入口，抽查相关单测和真实服务 E2E 脚本。没有因单测仅作冒烟而将“没有断言”列为缺陷；重要行为应通过真实服务和隔离故障场景验收。
- 当前没有可调用的知识图谱工具，无法核实 `D-claude-Nora` 索引覆盖与新鲜度。本轮使用有边界的源码搜索和调用链追踪，不宣称逐文件穷尽。
- “源码确认”表示相关执行路径和条件明确存在，不表示已经在生产触发。涉及永久删除、密钥、取消故障和进程竞争的场景没有在生产执行。
- 真实页面核对：首页、已交付成果的会话 `sess-1791505854882`、Agent 设置页，以及未发送草稿的切页与返回。临时评审标签页已关闭，原用户标签页保留。

## 当前能力清单

| 能力域 | 已有实现 | 边界与评价 |
| --- | --- | --- |
| 模型接入 | LangChain4j 流式客户端；OpenAI Chat、Responses、Anthropic 分支 | Ollama 当前进入 OpenAI 兼容分支，独立原生 `/api/chat` 未实现；不能把设置文案等同于原生协议支持 |
| Agent 循环 | 模型、工具结果、继续请求的循环；可连续调用工具，无须每次先输出说明 | 当前是单模型循环，默认最多 100 轮；已读注册与执行链未发现子 Agent 委派工具 |
| 工具并发 | 满足风险、名单和去重条件的整批调用可并发，最多四个 | 不满足条件的批次串行；已经支持并发，不能列成缺失 |
| 上下文与记忆 | 环境注入、技能目录及按需正文、工作区记忆、预算裁剪、旧工具结果归档回读、图片回收 | 显式引用仍有重复裁剪；历史编辑没有保持相同的引用契约 |
| 运行中交互 | 审批、提问、浏览器人工接管、插入新要求；插入先持久化，再在请求边界消费 | 普通排队仍由前端推进；提问结束后的运行状态有缺口 |
| 流与恢复 | SSE 回放、序号、缺口提示、活动轮次恢复、重启后中断/未知状态识别 | 有恢复能力；不接管旧执行线程、不自动重放工具。重连退订与最终持久化仍需修正 |
| 命令执行 | 自动注册 PowerShell/sh；执行 ID、短等待后后台运行、日志、查询、停止、进程树回收、总时限 | 当前默认短等待 3 秒、总时限 30 分钟，可配置或逐命令覆盖；最多四个活进程。没有 PTY/stdin 交互能力 |
| 内置浏览器 | 会话 Context、多标签、页面定位、截图/DOM、采集、接管、CSV 重读及哈希验收 | 所有会话的操作仍共用一条执行队列，慢操作会形成跨会话阻塞 |
| MCP | STDIO、HTTP、SSE；注册与测试、eager/lazy、完整输入 Schema、文本/图片 | 生命周期、凭据刷新与风险分流存在问题；成功的结构化/资源结果保存不全。SDK 已处理工具分页，未发现分页缺失 |
| 文件与成果 | 上传、预览、工作区编辑、冲突检测、未保存保护、编辑记录、交付文件卡片与登记 | 内部超链接新标签和打开方式设置已有实现；首页没有完整展示聊天成果 |
| 知识库 | 文本抽取、分块、向量与关键词融合、范围过滤、重排、降级状态、重建与版本发布 | 更新先构建后发布；删除/恢复有版本化通知补偿。扫描 PDF 的 OCR 明确暂不支持 |
| 数据源 | 连接、Schema、SQL/Redis 查询、历史、保存定期查询 | 结果与实际执行 SQL 的快照没有贯通页面切换和任务保存 |
| 自动任务 | 手动、每日、每周，时区与计划预览；规则专属会话、执行记录、通知及不同终态 | 文件/错误触发明确未开放；调度扫描会被执行占住；已建规则不能编辑保存 |
| 设置与导航 | Agent 分类管理、模型/技能/MCP/环境变量/网络、全局默认行为、快速查找 | 全局权限依赖标签页缓存；旧会话和具体资源缺少直接到达路径 |

## 必须先修

### 1. P1：结果保存失败后仍报告完成

- **负责什么：** 最终回答、插入分段和工作过程保存后，用户应能刷新并再次查看。
- **问题：** 助手消息事务失败后，Runner 只记录警告，仍可将运行写为 `completed` 并发出 `done`；启动恢复只查非终态运行。触发条件是“助手消息写失败，而随后终态写成功”。
- **最小修正：** 将助手消息和运行终态提交到同一个服务事务，成功提交后才宣布完成；保存失败保留可恢复状态，恢复只保存结果，不重放已执行工具。
- **不修的影响：** 用户收到完成提示，刷新后却丢回答和过程；为了找回结果可能重复执行写操作。

证据：[ChatTurnRunner.java](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:396)、[终态发布](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:412)、[恢复查询](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatStoreService.java:479)。源码确认，未注入数据库故障。

### 2. P1：停止、删除和历史截断没有共同的执行边界

- **负责什么：** 用户控制一个会话的活动轮次，并能安全删除或回退历史。
- **问题：** 停止先断本地流，取消请求忽略 HTTP 失败并吞网络错误；还有三秒后直接显示停止的兜底。删除和截断接口没有取消或等待活动轮次，删除只是把数据库记录标为取消，执行线程仍可能继续。
- **最小修正：** 前端区分“正在停止”和“已停止”，以服务端终态确认；后端在同一个会话控制入口协调发送、取消、删除和截断，运行中修改历史必须先取消并完成，或明确拒绝。
- **不修的影响：** 用户看到停止/删除，以为旧工作已经结束，但工具继续改变文件或环境；随后重试形成两份工作。独立后台命令是否随停止结束也应在界面明确，不能用断流代替进程终态。

证据：[useChat.ts](/D:/claude/Nora/nora-web/src/hooks/useChat.ts:633)、[取消请求](/D:/claude/Nora/nora-web/src/lib/api/agentApi.ts:321)、[侧栏删除](/D:/claude/Nora/nora-web/src/components/layout/Sidebar.tsx:58)、[后端删除](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentController.java:453)、[后端截断](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentController.java:466)、[实际取消入口](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentController.java:362)。源码确认，未停止或删除生产任务。

### 3. P1：工作区风险判断使用了不同的路径解析

- **负责什么：** 区分工作区内操作与区外写入、不可逆删除，并执行相应审批。
- **问题：** Linux 下 `delete path="..\\outside.csv"`，风险层没有统一反斜杠，执行层却会转换后访问父目录。`move/copy to=".."` 也会被当作低风险区内目标，实际写入父目录；真实链接目标没有进入相同边界判断。
- **最小修正：** 风险层复用执行层的目标解析，源和目标都使用同一个规范化、真实边界判定；不能用另一套字符串规则猜路径。
- **不修的影响：** FULL 或无人值守任务可绕过原本应触发的区外/不可逆操作批准。

证据：[RiskClassifier.java](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/RiskClassifier.java:304)、[执行端解析](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/AgentWorkspaceService.java:201)、[审批分支](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:231)。字面路径差异源码确认；链接变体仍需隔离目录验收。

### 4. P1：MCP 泛用调用改变了内层工具的风险

- **负责什么：** 无论工具如何被发现和调用，同一个动作应执行同样的批准规则。
- **问题：** 直接调用 `mcp__phone__photos_manage` 的 `trash_purge` 被判 CRITICAL；通过 `manage_mcp action=call` 包装同一动作只判 HIGH，然后直接发往服务器。FULL/自动任务因此可以改变该动作的批准结果。
- **最小修正：** 先解析真实服务器、工具及参数，再走同一套分类与审批；eager/lazy 是发现方式，不应成为权限差异。
- **不修的影响：** 永久清除照片等不可逆动作可通过另一条正常工具入口绕过强制批准。

证据：[泛用风险分支](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/RiskClassifier.java:113)、[真实 MCP 工具分类](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/RiskClassifier.java:183)、[泛用调用执行](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpManagementTools.java:148)。源码确认；只能用隔离 MCP 记录是否收到请求来验收，不能用真实照片测试。

### 5. P1：secret 标记没有贯通输出和展示

- **负责什么：** 环境变量能供执行使用，但标记为 secret 的值不应意外进入日志、界面和模型上下文。
- **问题：** 变量列表掩码不保护命令的裸输出；当前正则也漏掉带引号的 JSON 键，例如 `{"token":"CANARY"}`。步骤标题、typed target 和审批展示仍可能拿到原始命令，处理 rawArgs 不能覆盖这些出口。
- **最小修正：** 复用统一脱敏出口，覆盖结构化凭据字段、已知 secret 值、标题/参数/审批/错误/日志/回填与持久化；流式输出保留跨块保护，执行端仍收到精确值。
- **不修的影响：** 一次普通 `printenv`、诊断脚本或带 token 的 MCP 结果就可能暴露凭据，并留下可回载副本。

证据：[执行环境注入](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/TerminalService.java:168)、[输出处理](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/TerminalService.java:343)、[工具结果正则](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolExecutor.java:1148)、[步骤内容](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:625)、[审批内容](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:1085)。源码确认；未读取或输出真实密钥，验收应只用 canary。

### 6. P1：全局权限降低后，另一个标签页继续使用旧权限

- **负责什么：** Agent 页宣称权限与思考等级保存于服务端，应用到后续请求。
- **问题：** 标签 A 从 `full` 改为 `ask` 后，持续在线的标签 B 不会自动同步，仍在请求中发送缓存的 `full`。后端直接解析请求字段作为执行权限，服务端新默认值没有参与合并。
- **最小修正：** 服务端负责全局默认值；确实需要会话覆盖时，用明确的覆盖字段与界面。客户端同步并显示实际生效值，避免把旧缓存当成新的用户选择。
- **不修的影响：** 用户已经降低权限，另一窗口的后续 HIGH 操作仍自动执行；设置页的承诺不成立。

证据：[设置说明](/D:/claude/Nora/nora-web/src/components/agent/AgentBehaviorSettings.tsx:84)、[同步时机](/D:/claude/Nora/nora-web/src/hooks/useBackendHealth.ts:74)、[发送缓存值](/D:/claude/Nora/nora-web/src/hooks/useChat.ts:349)、[后端实际取值](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentController.java:171)、[设置端约定](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentSettingsController.java:60)。源码确认，未改变生产权限。

### 7. P1：长自动任务占住扫描，还缺少运行前的持久记录

- **负责什么：** 到点发现任务、领取计划点，然后执行并记录结果。
- **问题：** 扫描循环同步等每条规则执行完；A/B 都在 09:00 到点时，A 运行超过十分钟，B 随后可能被记为错过计划。领取先推进 `next_run_at`，执行记录却在完成后才插入，二者之间崩溃会留下“计划已推进，没有本次运行记录”的空档，`started_at` 默认值实际也是完成时插入的时间。
- **最小修正：** 在短事务内领取计划点并创建有 `scheduledAt/startedAt` 的运行记录，随后交给受控执行器；完成时更新同一行。保留每规则不并发和现有会话执行路径，扫描不等待模型工作。
- **不修的影响：** 增加第二个长定时任务就可能漏跑；重启后难以判断已领取任务是否执行，界面还会把结束时间当作开始时间。

证据：[调度入口](/D:/claude/Nora/nora-api/services/automation-service/src/main/java/com/nora/automation/config/AutomationConfig.java:64)、[领取与同步执行](/D:/claude/Nora/nora-api/services/automation-service/src/main/java/com/nora/automation/service/AutomationService.java:352)、[完成后插入记录](/D:/claude/Nora/nora-api/services/automation-service/src/main/java/com/nora/automation/service/AutomationService.java:254)、[时间字段默认值](/D:/claude/Nora/nora-api/services/automation-service/src/main/resources/db/migration/V1__init.sql:22)、[十分钟宽限](/D:/claude/Nora/nora-api/services/automation-service/src/main/java/com/nora/automation/service/ScheduleCalculator.java:29)。源码确认；未触发生产规则或重启服务。HTTP 300 秒是读取等待设置，不能据此声称整个 Agent 最多运行五分钟。

## 应随后修

### 8. P2：普通排队消息依赖会话页面推进

- **负责什么：** 正在运行时发送新要求，可排队、插入、撤回和编辑。
- **问题：** 插入由后端接收，但普通队列只由挂载中的前端 effect 推进。离开 A 时第一轮继续执行，第二条保留在本地却要等用户回到 A 才开始；后端普通新轮遇到活动轮次只返回 `TURN_IN_PROGRESS`。
- **最小修正：** 后端以稳定消息 ID 接收并推进普通队列；前端展示和编辑，失败/取消后明确暂停。沿用现有轮次边界和插入协议，不再创建另一套临时调度。
- **不修的影响：** “已排队”并不保证后台会执行，刷新、跨设备或切页时行为难以理解。

证据：[队列推进](/D:/claude/Nora/nora-web/src/hooks/useChat.ts:511)、[卸载后完成处理](/D:/claude/Nora/nora-web/src/hooks/useChat.ts:377)、[后端活动轮判断](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentController.java:146)。源码确认，未发送新消息实测。

### 9. P2：切页或切会话会丢未发送草稿

- **负责什么：** 保留用户正在组织的文字、引用和限定范围。
- **问题：** 它们是 `useChat` 的组件状态，会话切换重建组件，进入其他业务页也会卸载。真实页面输入临时草稿、切到 Agent 页、返回会话后，textarea 的值为空。
- **最小修正：** 按 session ID 保存 `{text, refs, retrievalScope}` 草稿，服务端接受发送后再清空。引用上传与未发送草稿的归属也应保留，不能仅保存字符串。
- **不修的影响：** 用户去检查文件、设置工具或查看另一会话时，回来必须重新输入并重新选资料。

证据：[草稿状态](/D:/claude/Nora/nora-web/src/hooks/useChat.ts:44)、[会话重建](/D:/claude/Nora/nora-web/src/app/chat/page.tsx:174)。源码与真实页面确认，临时文字未发送。

### 10. P2：显式引用没有保持完整的端到端契约

- **负责什么：** 将用户指定的正文、稳定引用身份及检索范围带进每次相应请求。
- **问题：** 文件/技能先保留 8K 字符，并在末尾给补读方式，assembler 又统一截为 4K 字符，补读提示也可能被裁掉。历史消息编辑直接使用含内部引用串的正文，重新发送没有原 `taskContext`，原“仅检索文档 A”范围会恢复默认。
- **最小修正：** 编辑与发送始终使用正文、refs、scope 三个字段；引用预算只处理一次，稳定身份和补读入口放在不可裁掉的元数据位置。沿用已有原文归档/回读工具。
- **不修的影响：** 用户看到资料已引用，模型实际缺关键后半段；仅改一句话就可能扩大检索范围，并把内部引用格式暴露给用户。

证据：[文件引用](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/MessageRefResolver.java:238)、[技能引用](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/MessageRefResolver.java:298)、[再次裁剪](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatContextAssembler.java:697)、[历史编辑框](/D:/claude/Nora/nora-web/src/components/chat/ChatMessageItem.tsx:335)、[编辑重发](/D:/claude/Nora/nora-web/src/hooks/useChat.ts:679)。源码确认，未调用真实模型验证后半段事实。

### 11. P2：SSE 重连注册的监听器不能准确退订

- **负责什么：** 刷新、切页和断线后重连当前执行流，并释放旧连接。
- **问题：** 注册对象是 `gate::onEvent`，退订传入的却是 `forward`，按对象删除无法移除实际监听器。长轮次反复重连会保留失效 emitter 和序号集合，每个新事件仍遍历旧连接。
- **最小修正：** 订阅返回准确的取消句柄，完成、超时和异常都通过它释放；保持既有回放与去重语义。
- **不修的影响：** 使用越久、重连越多，事件处理负担越大；慢连接的写出还可能影响持锁路径。

证据：[监听注册](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/TurnStreamRegistry.java:210)、[错误退订对象](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/TurnStreamController.java:180)、[按对象删除](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/TurnStreamRegistry.java:259)。对象差异源码确认，实际连接数和延时未压测。

### 12. P2：MCP 连接没有原子所有权，刷新也没有重建凭据

- **负责什么：** 一台注册服务器对应一个受管连接和进程树，并支持配置更新。
- **问题：** 两个会话首次调用同一 STDIO 服务时，无锁的 `get → initialize → put` 可启动两棵进程树，后写入覆盖所有权，回收只处理最后一份。已连接服务更新 `${KEY}` 后执行 refresh，连接池只比 URL，仍复用旧 headers/env。
- **最小修正：** 按 server ID 串行建连、刷新和驱逐，将 client/URL/process/tree 作为同一个受管连接；显式 refresh 驱逐并按最新配置重建，失败关闭尚未登记的资源。
- **不修的影响：** 共享 MCP 会遗留进程；用户已经更换凭据且看到刷新成功，调用却仍使用旧值。

证据：[McpClientPool.java](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpClientPool.java:59)、[所有权登记](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpClientPool.java:139)、[刷新实现](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:340)、[工具刷新说明](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolsSpec.java:893)。源码确认；并发进程数量及初始化异常时 SDK 的资源回收未实测。

### 13. P2：慢浏览器操作影响其他会话和人工接管

- **负责什么：** 多个会话可以使用自己的浏览器，用户能查看画面并接管。
- **问题：** 导航、采集、截图和控制共用唯一 actor；慢下载在事件中同步保存。外层 future 超时并不终止已执行操作，所以另一会话的画面和接管也要排队。
- **最小修正：** 明确每会话的 Playwright 所有权与执行队列，给实际下载生命周期期限；不能仅扩大线程池，让现有句柄跨线程使用。先用两个会话测阻塞，再选保持线程归属的隔离方式。
- **不修的影响：** 一个网页慢或卡住，整个浏览器能力都像失去响应；用户也无法及时接管查看。

证据：[共用执行器](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserService.java:46)、[操作等待](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserService.java:285)、[下载保存](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserSession.java:47)。结构确认；跨会话慢下载影响时长待隔离实测。

### 14. P2：成功 MCP 结果可能丢成无返回内容

- **负责什么：** 将 MCP 工具结果交给模型，并能在历史与工作区回读。
- **问题：** 当前仅完整处理 text/image，未读取 SDK 的 `structuredContent`，资源链接、嵌入资源和音频只保留类型占位。合法的 `content:[], structuredContent:{...}` 成功结果会丢掉真实数据。
- **最小修正：** 先保留结构化结果和资源内容/引用，继续经过现有预算与归档；暂不支持的媒体明确说明限制并保存可回读入口。
- **不修的影响：** 服务端实际成功，模型却得不到资料，容易误判失败、重复查询或自行猜测。

证据：[结果转换](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/McpServerService.java:671)。源码及本地 MCP SDK 2.0.1 类型确认，未执行外部 MCP。

### 15. P2：任务与成果总览既不完整，也不能持续反映状态

- **负责什么：** 让用户知道后台在做什么、做完了什么，并能回到来源会话。
- **问题：** 首页成果只读自动任务记录，文案却承诺普通助手成果也会出现；运行列表仅挂载时取一次。请求失败也被折叠为空列表，不能区分“没有任务”和“读取失败”。
- **最小修正：** 汇总现有会话运行、自动执行和成果登记，保留来源 ID；活动列表订阅变化或定期刷新，错误与空态分开。先统一查询与入口，不另造任务系统。
- **不修的影响：** 用户必须逐个会话寻找文件；停留页面不知道任务已结束，后端异常还被界面掩盖成没有工作。

证据：[只读自动执行](/D:/claude/Nora/nora-web/src/components/home/RecentResults.tsx:32)、[首页空态承诺](/D:/claude/Nora/nora-web/src/components/home/RecentResults.tsx:51)、[一次性运行列表](/D:/claude/Nora/nora-web/src/components/automations/RunningTasks.tsx:33)。源码及真实页面确认：`sess-1791505854882` 已有 CSV、验收单和 Markdown 成果卡片，首页仍显示没有任务执行记录。

### 16. P2：已创建的自动规则不能编辑保存

- **负责什么：** 调整现有任务的时间、指令或目标连接，同时保留身份和历史。
- **问题：** 建立后的规则只有启停和立即执行；新建表单总是创建新规则，前后端没有 update 链路。`needs_config` 提示“请编辑补充后再启用”，却没有编辑入口，展示映射还丢掉原始动作/日程配置。
- **最小修正：** 用同一份原始规则 DTO 和现有表单支持详情及更新，复用日程/动作校验，保留规则 ID、连接和历史；需配置状态直接进入补充配置。
- **不修的影响：** 每次改时间或指令都要另建任务，历史分散，旧任务可能继续运行。

证据：[规则操作与提示](/D:/claude/Nora/nora-web/src/components/automations/AutomationList.tsx:59)、[始终创建](/D:/claude/Nora/nora-web/src/components/automations/NewAutomationModal.tsx:124)、[配置映射](/D:/claude/Nora/nora-web/src/lib/services/automationsApi.ts:62)、[后端接口](/D:/claude/Nora/nora-api/services/automation-service/src/main/java/com/nora/automation/controller/AutomationController.java:1)。源码确认，未修改真实规则。

### 17. P2：查询结果和保存任务没有绑定实际执行快照

- **负责什么：** 在正确数据源显示正确 SQL 的结果，并可将已验证查询设为定期任务。
- **问题：** A 的慢查询期间切到 B，A 的旧请求回来仍直接写当前 result，可能显示在 B 下。执行成功后再改 SQL，保存任务取的是当前草稿，结果却仍是上次查询的，名称还固定成示例业务。
- **最小修正：** 每次执行保存 `{connectionId, executedSql, result}`，用请求序号拒绝旧请求回填；界面明确区分安排已验证查询和安排当前草稿，名称基于实际查询或用户输入。
- **不修的影响：** 用户可能把 A 的数据当作 B 的数据判断，也可能安排一条从未验收的查询。

证据：[切连接处理](/D:/claude/Nora/nora-web/src/components/data-sources/QueryConsole.tsx:67)、[异步回填](/D:/claude/Nora/nora-web/src/components/data-sources/QueryConsole.tsx:103)、[保存当前 SQL](/D:/claude/Nora/nora-web/src/components/data-sources/QueryConsole.tsx:127)、[控制台复用](/D:/claude/Nora/nora-web/src/app/data-sources/page.tsx:115)。源码确认；没有执行生产 SQL。

### 18. P2：旧会话与搜索结果不能直接到达

- **负责什么：** 用户不用记 session ID，也能找到早先会话和具体资料。
- **问题：** 侧栏只取最近八个会话，没有已发现的全部历史入口，快速查找不索引会话。文件、任务等结果虽展示具体名字，点击却只进入模块首页；知识库还只索引前二十份文档。
- **最小修正：** 增加可搜索的全部会话列表；搜索条目携带资源 ID，复用现有查看器或定位参数，直接打开目标。
- **不修的影响：** 使用越久，找旧工作越依赖复制 URL；搜到文件后还要再找一次，搜索节省不了步骤。

证据：[最近会话限制](/D:/claude/Nora/nora-web/src/components/layout/Sidebar.tsx:88)、[搜索索引](/D:/claude/Nora/nora-web/src/components/home/CommandPalette.tsx:55)、[只跳模块](/D:/claude/Nora/nora-web/src/components/home/CommandPalette.tsx:125)。源码确认；超过八个会话的真实检索路径待页面验收。

### 19. P2：回答澄清问题后仍显示等待确认

- **负责什么：** 用户回答问题后，任务应重新显示执行或思考中。
- **问题：** 提问写 `awaiting_approval`，恢复运行状态只响应新的 running 工具步骤；回答后 ask_user 直接发 completed。如果模型接着长时间思考/回答而不启动新工具，状态一直等到最终结束才纠正。
- **最小修正：** 交互等待结束时明确恢复运行态，让提问和批准共享同一个状态转换，而非等待下一工具顺便修正。
- **不修的影响：** Agent 已在继续，任务页却仍要求用户处理，用户可能重复回答或误判卡住。

证据：[提问进入等待](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:279)、[仅 running 恢复](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:158)、[作答后直接完成步骤](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:299)。源码确认；执行并未因此被阻塞，问题是状态误导。

### 20. P2：刷新后与失败后的真实计量不能回溯

- **负责什么：** 用户能查看消耗，并比较一个任务的耗时、模型和失败成本。
- **问题：** 轮内累计 usage 后，后续请求失败会走异常返回，Runner 得到空 usage；数据库没有保留实际 input/output/total、TTFT 和实际模型渠道快照，历史消息回载也将相关值置空。
- **最小修正：** 将已获得的真实计量和模型渠道保存到运行记录，失败/取消也保留已知部分；未知维持空值，估算值明确标记，不能伪造精确 token。
- **不修的影响：** 当前流上看得到消耗，刷新就不能查询；排查慢任务时也难区分模型时间、工具时间、等待用户时间和失败成本。

证据：[轮内计量](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatOrchestrationService.java:477)、[异常后的 usage](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:333)、[现有保存字段](/D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatStoreService.java:167)。源码确认；当前上下文估算和即时 token 显示仍存在，不能表述为完全没有计量。

## 后续能力建设

下面是明确的能力边界及按实际需求投入的方向，不是要求本次重做整套系统。

| 方向 | 何时有价值 | 最小建设方式 |
| --- | --- | --- |
| 子 Agent 委派 | 同时独立研究多个方向、审查代码、验证产物，需要不同上下文和可等待的结果 | 复用现有运行/步骤记录，增加父子任务关联、独立输入范围、取消与收束；先支持一类清晰委派，不先建立任意工作流图 |
| 交互终端 | 命令确实需要 stdin、PTY 或人工继续交互 | 先明确工具不支持交互，并优先使用官方非交互安装参数；需要时再加入受管终端输入和人工接管，保留现有后台执行 ID |
| 扫描资料 OCR | 用户经常上传扫描 PDF、图片文档，希望进入知识库 | 在现有抽取链补成熟 OCR 组件，保留页码、来源和识别告警；无需替换已有知识库索引设计 |
| 更多业务验收 | 文件修改、数据库分析、环境配置需要像浏览器采集一样有可检查的完成条件 | 复用现有产物与过程证据，按领域做少量明确检查；模型结束与业务验收分开，不把一句“完成”当作独立证据 |
| 通用 MCP 授权 | 接入明确要求标准 OAuth 发现、授权与刷新流程的服务 | 先核对目标服务与 SDK 支持，再补可复用授权链；现有 headers/env 与专用 GitHub OAuth 不能宣称覆盖所有 MCP 授权方式 |

## 建议的实施顺序与验收

| 批次 | 覆盖编号 | 完成标准 |
| --- | --- | --- |
| 第一批：可靠控制与权限 | 1–6 | 保存失败不发成功终态；取消失败不冒充停止；删除/回退不遗留执行；路径和 MCP 两种入口批准结果一致；canary 不出现在任何展示/持久化出口；双标签权限降级真实生效 |
| 第二批：执行离开页面也成立 | 7–9、11–12 | 两个同时间长任务都被及时领取；执行记录在启动前存在；离开会话后队列按约定推进；草稿跨页面恢复；SSE 断开后监听数量回落；同一 MCP 只建连一次，刷新使用新 canary |
| 第三批：业务交付与导航 | 10、15–18 | 编辑重发保持引用范围且补读入口可见；聊天成果能从首页到达；规则可编辑并保留历史；查询显示与保存的 SQL/连接匹配；旧会话和资源能直接检索打开 |
| 第四批：长任务体验与协议完整性 | 13–14、19–20 | 慢浏览器不阻塞另一会话接管；结构化 MCP 结果可回读；回答后状态立即恢复；刷新、失败和取消后保留已知真实计量 |

这些检查应在隔离目录、测试 MCP、测试数据源和可控上游中通过真实 API/浏览器验证。优先补多标签、切页、故障与跨入口用例；现有单页成功路径的冒烟通过不足以验证这些边界。不要对真实照片执行清除，不要用真实密钥做脱敏测试，也不要重放结果未知的生产写操作。

设计上应坚持三个简单边界：后端拥有执行与排队；前端拥有草稿和展示；所有工具入口复用同一解析、权限与结果处理。任务与成果总览汇集已有记录即可，暂不需要新微服务、重型工作流框架或全面改写 harness。

## 本轮未核实

没有重新运行全量构建/测试，没有调用模型或对生产注入故障；没有做移动端、键盘无障碍、慢下载压测、通用 OAuth 全兼容测试。环境/数据源/通知只追踪本次相关主链，没有逐个审查所有管理接口；潜在提示注入的可利用性和旧 PID 接管也未测试。性能结论仅确认阻塞结构，不给未经测量的吞吐或提速数字。

优先修 1–7，再把排队、草稿和任务成果做完整，随后按真实场景增强委派和验收能力。
