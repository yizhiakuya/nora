# Nora 高级 Agent 能力调研

日期：2026-10-05。结论：优先做跨网站办事、结果验收与经验证的经验学习；随后加入长期目标与事件触发。让已有工具组合形成完整工作流，比继续增加独立场景入口更能扩大 Nora 的用途。

本文的优先级是结合 Nora 当前实现作出的工程判断，外部项目只提供实现依据。调研未运行这些框架的性能对比，也未将它们安装或接入 Nora。

## 当前具备的基础

当前实现已经有模型工具循环、知识检索、SQL、文件、终端、环境管理、MCP、技能、定期任务、运行记录和成果交付。高级能力应复用这些接线。

| 已核对的实现 | 实际含义 | 可以升级的部分 |
| --- | --- | --- |
| [ChatOrchestrationService.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatOrchestrationService.java:441) | 一轮对话中循环调用模型和工具，处理取消、上下文压缩和工具失败 | 增加跨轮次的目标、工作单元和验收条件 |
| [StaleRunRecovery.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/config/StaleRunRecovery.java:12)、[ChatStoreService.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatStoreService.java:387) | 重启后把遗留运行标记 interrupted，供用户重试 | 保存可恢复进度，恢复前核对已经产生的效果 |
| [ChatToolsSpec.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolsSpec.java:386) | USER.md / MEMORY.md / 日记承载长期偏好、事实和观察 | 按任务检索历史经验，记录来源、适用条件与失效信息 |
| [AgentSkillService.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/AgentSkillService.java:64) | 可以创建、更新技能，目录与正文按需加载 | 把成功任务转成参数化方法，并验证复用效果 |
| [ChatTurnRunner.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:308)、[ChatStoreService.java](../../nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatStoreService.java:437) | 异常时保存错误描述，按 session 和任务签名读取最近反思 | 提取有效恢复策略和成功方法，供不同会话检索复用 |
| [AutomationService.java](../../nora-api/services/automation-service/src/main/java/com/nora/automation/service/AutomationService.java:95) | 当前接受 manual / daily / weekly，文件与错误事件触发明确未接通 | 外部变化触发同一长期目标，并保留事件游标与处理结果 |
| [AutomationService.java](../../nora-api/services/automation-service/src/main/java/com/nora/automation/service/AutomationService.java:257) | 已按统一业务终态区分成功、部分完成、取消与未知 | 进一步判断用户目标是否满足，而不只消费一轮运行状态 |

09-26 的审计文档是历史证据；其中 F2/F3 的终态修复已在当前源码中出现，不能继续把它们作为尚未修复的缺口。当前运行记录、SSE 续接和历史读取也不能直接等同于跨进程恢复执行。

上述现状来自列出的源码范围。本会话没有 codebase-memory MCP 工具，使用定向源码读取；未调用用户的外部浏览器 MCP，也未对仓库之外的接入情况作完整盘点。

## 值得做的六种能力

### 1. 跨网站办事 Agent

用户给目标，Nora 在已连接的浏览器里导航、查询、导出、填写和核对，再把网页数据接到现有文件、SQL 和终端工具。直接覆盖没有业务 API 的后台，明显扩大可执行任务范围。

例如：从指定后台导出最近七天订单，与指定数据库或本地清单对账，找出缺失、重复和金额差异，生成可复查的结果文件。这里的“跨网站”要保持每份数据的来源和时间，避免把不同店铺、账户或日期的数据混在一起。

实现入口建议复用 Microsoft Playwright MCP。官方实现支持可访问性快照，也支持持久浏览器配置或扩展连接现有登录会话。Nora 已有 MCP 接入层，先验证标准工具与真实模型的配合，再补必要的产品接线。[Playwright MCP 官方说明](https://github.com/microsoft/playwright-mcp/blob/main/README.md)

第一版需要浏览器与目标会话对应、下载文件进入成果链路、实际页面状态可见，以及登录失效或页面改变时有明确恢复入口。实际浏览器位置应可选择本机或服务器：服务器浏览器不能直接继承用户电脑的登录状态。网页内容按资料处理，操作继续沿用 Nora 的工具权限语义。

### 2. 经验证的经验学习

第一次完成一个复杂任务后，保存真正有效的方法：适用场景、输入参数、数据来源、步骤、错误恢复方式、验收条件和成果结构。下次“按上次的方法做，日期改成这个月”时，检索方法并执行；遇到环境变化后更新有证据支持的部分。

Nora 已能管理技能，增量在于把任务结果与技能版本关联，并形成“执行—验收—提炼—复用—再验收”的循环。现有异常反思主要是错误字符串；它不足以证明模型已经总结了修复方法，也不足以支撑跨会话学习。

Hermes 的公开实现将可复用流程作为程序性记忆，允许 Agent 在多步任务、错误恢复或用户纠正后创建与改进技能。这个思路适合复用 Nora 的技能管理与工作区，而不是另外维护一套技能仓库。[Hermes Skills System](https://hermes-agent.nousresearch.com/docs/user-guide/features/skills/)

候选技能至少携带来源 run、适用条件和验收记录；正文不复制一次性下载 URL、凭据或整段工具日志。方法记忆与个人偏好分别处理，新证据能使旧方法失效。推广复用前用另一组真实参数验证，避免把一次偶然成功固化成标准方法。这种改进发生在经验与执行方法层面，不代表模型权重被训练。

### 3. 长期目标 Agent

把“持续完成一个目标”作为任务对象，保存已确认事实、当前计划、已完成工作、待完成条件、下一次唤醒原因和成果。浏览器关闭后服务继续推进；进程重启、网络中断或等待外部条件后，可以从核对后的进度继续。

例如：“这个月持续整理指定目录里的资料，补齐缺失项，维护一份总清单；新文件到来后继续更新。”区别在于多次执行共同推进同一个目标，完成条件和剩余工作不会在每次定期运行时重新猜测。

Anthropic 的长任务实践强调增量工作、明确完成条件和可供下一轮读取的成果；LangGraph 将持久检查点与跨会话记忆分别管理，并支持从检查点重放。[Anthropic 长任务实践](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)、[LangGraph Checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers)

Nora 可以保留现有串行模型工具循环，在目标层保存工作单元和检查点，复用 PostgreSQL、运行记录与调度。聊天历史保存下来仍不足以恢复执行：需要确定下一步，以及已执行动作的结果。结果不明的写操作先读取外部状态再决定下一步；不能把任意工具调用机械重放视为“只执行一次”。LangGraph 文档也明确指出检查点之后的节点重放会再次调用模型或 API。

### 4. 事件驱动的主动 Agent

收到新文件、数据变化、服务异常或已接入工具的事件后，检索关联目标与历史状态，判断变化是否需要行动，再推进该目标。保留事件 ID、对象版本和处理游标；重复事件不会重复产生同一个成果或通知。

例如：“新报表进入指定目录后更新总表；发现异常时结合历史数据分析，只有新增异常或结果变化时通知我。”连接器必须实际提供事件或可轮询的变化查询，不能只在提示词里写“持续监听”。

OpenClaw 的 heartbeat 提供周期性主动检查，并对无变化或无需通知的结果保持静默。它提供了有用的产品语义，但周期检查与真正事件投递是不同机制。[OpenClaw Heartbeat](https://docs.openclaw.ai/gateway/heartbeat)

Nora 已有自动任务和 Kafka 通知基础。第一版可以先接一个真实来源，例如文件生命周期事件或服务健康变化；日程仍走现有调度，同一来源的重复变化先合并，Agent 只处理需要语义判断的部分。

### 5. 结果验收与自动修复

执行后回读真实状态，判断是否满足目标：导出的文件能否打开、记录数是否匹配、数据库变更是否实际存在、网页是否呈现指定布局、所需资料是否全部覆盖。未满足时根据证据修正步骤，再验证；事实冲突或必要条件缺失时指出剩余问题。

这是前面几种能力的共同基础。工具返回成功、回答里出现“完成”、模型自评“正确”，都不能单独证明用户目标完成。Anthropic 的评测文章明确区分执行轨迹与环境中的最终结果，并建议按实际成果检查任务成功。[Anthropic Agent Evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

Nora 现有运行终态继续保留工具错误事实，另记录目标验收状态和证据，避免一次已恢复的参数错误永久掩盖最终成果，也避免没有成果却只凭文字判成功。验收先覆盖常用任务：文件存在与可读、数量与范围、来源对应、页面状态、数据库回读。证据不足的目标保持待核对，不由模型填补。

照片相册的真实验收已经说明价值：HTML 文件成功生成之后，查看器清理 body 属性仍使主题和网格失效；浏览器检查才发现问题。把这种回读与修复变成 Agent 的常规执行步骤，适用于所有任务。[相册验收记录](photo-growth-album-2026-10-05.md)

### 6. 编程式工具编排

大批量处理中，Agent 编写一个小程序调用受控工具、分页、过滤、去重和聚合，只把必要摘要与成果引用返回模型。例如导出数千条记录并对账，避免每一页数据都经过模型上下文。

Anthropic 的 MCP 实践将工具调用放到代码执行环境，减少工具定义和中间数据占用模型上下文。Browser Use 的 CodeAgent 也公开了批量抓取、处理并生成可重跑代码的例子。[Anthropic Code Execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)、[Browser Use 批量处理示例](https://docs.browser-use.com/customize/code-agent/example-products)

Nora 已有 run_command，新增价值应是标准代码运行环境、结构化工具调用、可复用数据句柄和每次调用的可观察记录。不能让代码路径绕过现有工具权限，也不必为了小任务先造通用编排语言。等首个真实批量流程证明模型往返成为瓶颈后再接这一层。

## 推荐落地顺序

| 顺序 | 交付内容 | 相对投入 | 对现有能力的增量 |
| --- | --- | --- | --- |
| 第一批 | 跨网站办事 + 结果验收 | 中 | 从可调用 API 的系统扩展到真实网页后台，交付经过核对的结果 |
| 紧接第一批 | 经验证的经验学习 | 中 | 同类工作换参数可以复用方法，成功经验跨会话保留 |
| 第二批 | 长期目标 + 可恢复进度 | 高 | 多轮、跨天、等待条件后持续推进一个目标 |
| 第二批之后 | 一个真实事件来源 | 中至高 | 变化主动触发目标，重复事件与无变化保持安静 |
| 批量任务出现瓶颈后 | 编程式工具编排 | 中至高 | 中间数据在程序中处理，模型聚焦判断与异常 |

投入是基于当前接线的相对估计，不是已经测得的工期。先用一个串行 Agent 打通闭环；用户现有“不主动用子代理”的约定仍适用。

首个建议演示：

> 帮我从指定后台导出最近七天订单，和指定数据库或本地清单对账，标出缺失、重复与金额差异，保存结果与来源。下次让我换日期重跑时，沿用这次验证过的方法。

数据来自用户指定的真实后台和连接；先做导出、分析与交付。复用 MCP、SQL、终端、工作区和现有成果查看器，首版无需新增一级技术入口。具体写操作按任务要求和现有权限执行。

```mermaid
flowchart LR
  G[用户目标与验收条件] --> P[选择方法与计划]
  P --> X[浏览器 / 文件 / SQL / 终端]
  X --> V[回读真实状态]
  V -->|未满足且可修正| P
  V -->|满足| D[交付成果与证据]
  D --> M[保存经验证的方法]
  M --> P
  E[新事件或条件变化] --> G
```

## 如何证明能力确实升级

遵守仓库约定：单测只冒烟，行为结果用真实服务、真实网页和真实文件验证。

1. 跨系统任务：后台真实导出，输入范围与行数可核对，异常结果能追溯到来源记录，文件能实际打开。
2. 方法复用：另开会话换日期重跑，确实检索原方法，生成对应新范围的正确结果；不能把旧结果再次交付。
3. 页面变化：一个真实定位条件变化后重新观察页面、调整步骤、再核对产物，恢复失败时明确剩余工作。
4. 可恢复任务：执行中重启服务，恢复前核对已完成文件与外部状态，剩余工作继续，已完成写操作不重复发生。
5. 主动处理：同一真实事件重复投递、以及没有变化的检查，不重复通知或生成同一个成果；新变化才推进目标。
6. 验收失败：工具调用成功但结果缺项、范围错误或页面样式未应用时，目标不能被标记已验收。

记录端到端成功率、重复成功率、人工介入次数、错误恢复率、每次任务用时与模型调用消耗。应与 Nora 当前同任务的表现比较；本文没有声称已经取得提升。
