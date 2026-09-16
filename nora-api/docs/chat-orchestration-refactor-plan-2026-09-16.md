# ChatOrchestrationService 拆分方案（2026-09-16）

> 基线：`2e0f2e3`，文件 4,197 行 / 96 方法（含重载），单文件占全部 Java 的 18%。
>
> **执行状态（2026-09-17 更新）**：四步全部完成并逐提交验证（`709b95d` / `e42590e` / `59fd154`）。
> facade 最终 **4,178 → 1,502 行（-64%）**；实际拆出 8 个类：ChatToolsSpec(490) /
> ChatToolExecutor(936) / UpstreamLlmClient(679) / ChatContextAssembler(579) /
> ModelResolver(84) / ModelCapabilityRegistry(107) / Texts(43) + 3 个共享类型文件
> （ResolvedLlm / StreamTurnResult / WireMessage）。每步均通过 122 测试 + 真实
> E2E（打包重启 + SSE 工具链路冒烟）。本方案文档保留作为「为什么这样拆」的决策记录。

## 一、现状诊断

### 1.1 文件内职责清单（按现有代码分区）

| 职责块 | 行范围（约） | 方法数 | 说明 |
|---|---|---|---|
| 对话主循环 | 207–562 | 8 个 chat() 重载 + 主循环 | 检索 → 工具循环 → 最终回答 |
| 工具步骤发射/审批 | 564–700 | emitToolStep ×3、finishToolStep、backfillToolMessage ×2 | 步骤生命周期 + 审批门 + 无人值守闸 |
| URL 导入 | 702–850 | importToWorkbenchFile、downloadBounded、importFromUrl、inferFilename | read_file import / manage_workspace import |
| 模型能力降级 | 909–1063 | vision/effort 拒绝记忆与重试判定、stripImages、textOfContent 等 | 上游 400 的各种降级策略 |
| 参数解析 | 1069–1363 | ParsedArgs、parseArgs(71 行)、normalizeArgs、buildApprovalRequest(168 行) | 模型 args JSON → typed input |
| 模型/渠道解析 | 1372–1598 | configured ×4、generateSessionTitle ×2、cleanTitle、resolveLlm、resolveFromStore、effectiveReasoningLevel、clientFor、resolveManagedSourceId | 解析链：请求 > store > 静态兜底 |
| **工具执行** | **1605–2166** | **executeTool(562 行)** | 11 个工具 + MCP 兜底分发 |
| 结果裁剪 | 2169–2250 | summarizeCommand、bounded、summarizeRows | 30K/10K 预算 |
| 流式上游 | 2286–2950 | streamTurn ×2、streamFinalAnswer ×2、streamUpstream(162)、streamUpstreamResponses(236)、applyReasoningRequest(53) 等 | 两套协议 SSE |
| **工具 Schema** | **2963–3426** | **toolsSpec(456 行)** | 11 个工具 JSON Schema 全硬编码 |
| 历史装配 | 3427–3630 | buildMessages、appendHistoryMessage(47)、rebuildableToolSteps、wireCostOf | 工具链 wire 重建 |
| 上下文预算 | 3635–3830 | compactForRound、recycleOldImages、messageTokens、logCalibration | ContextBudget 的应用层 |
| 系统提示/注入 | 3838–3950 | systemPromptWith、emitContextSteps、lastEmittedContext | 工作区/技能注入可见性 |
| 技能/杂项 | 3954–4090 | resolveSkill、skillNameList、scrubArgsForLog、friendlyUpstreamError(119) 等 | |
| 类型定义 | 全文件 | 14 个嵌套 record/interface | ToolOutcome、ParsedArgs、ResolvedLlm、ChatTurn、ChatEventConsumer 等 |

### 1.2 核心问题

1. **物理纠缠**：所有职责共享 16 个构造器注入的依赖和 2 组可变状态（`visionRejectedModels` / `effortRejectedModels` 缓存、`lastRecycledImages` / `estimateTokensFreed` 压缩统计），任何一块都拖家带口。
2. **两大巨石**：`executeTool`（562 行，11 路 if 分发）与 `toolsSpec`（456 行，11 个 schema 手写）——两者是同一批工具的「执行面」与「描述面」，却相隔 1300 行。
3. **协议分叉**：`streamUpstream` 与 `streamUpstreamResponses` 两套完整实现并存，共享的只有 `Tokens`/`TokenSink` 两个私有类型。
4. **测试依赖反射**：测试用 `getDeclaredMethod` 反射调 `scrubArgsForLog` / `parseArgs` / `executeTool` / `buildMessages` / `emitToolStep` / `toolsSpec`——拆分时这 6 个名字的可见性/位置变化会直接弄断测试。

## 二、目标形态

### 2.1 拆成 8 个类（均留在 `com.nora.agent.service` 包）

```
ChatOrchestrationService        (~900 行,门面+主循环)  ← 对外 API 不变
├─ ChatToolExecutor             (~750 行)  executeTool 的 11 个 handler + MCP 兜底
├─ ChatToolsSpec                (~500 行)  toolsSpec 数据化(静态 schema 装配)
├─ UpstreamLlmClient            (~700 行)  两套协议 SSE + 请求体构建 + 错误友好化
├─ ChatContextAssembler         (~450 行)  buildMessages/历史重建/compact/recycle/预算
├─ ModelResolver                (~250 行)  模型解析链 + 标题生成
├─ ModelCapabilityRegistry      (~60 行)   降级缓存(vision/effort 拒绝记忆,进程内)
└─ ToolStepEmitter              (~250 行)  步骤生命周期 + 审批门 + 参数解析/脱敏
```

> 降级缓存为什么独立成类：它被三处跨类读写——主循环（chat 内重试逻辑写入）、
> `ToolStepEmitter.backfillToolMessage`（读 vision 拒绝）、`UpstreamLlmClient.applyReasoningRequest`
> （读 effort 拒绝），留在任何单一类里都会造成反向依赖。

### 2.2 各类的职责与依赖

| 类 | 迁移的方法（现行号） | 注入依赖 | 公开面 |
|---|---|---|---|
| **ChatOrchestrationService**（保留） | chat() ×8（207–562 主循环部分）、emitContextSteps、systemPromptWith、logCalibration、ChatTurn/ChatEventConsumer 类型 | 全部（编排者持有） | 现有公开 API **完全不变**：chat ×8、configured ×4、generateSessionTitle ×2、ChatTurn、ChatEventConsumer、TokenUsage |
| **ChatToolExecutor** | executeTool(1605–2166)、bounded、summarizeRows、summarizeCommand、guardSql/guardService（2217 附近）| SqlToolClient、ServiceLogClient、WriteSqlClient、ContainerControlClient、DataSourceManageClient、ServiceManageClient、FileToolClient、McpServerService、TerminalService、AgentWorkspaceService、AgentSkillService、ObjectMapper | `ToolOutcome execute(name, args, parsed, liveOutput)`；`ToolOutcome bounded(...)` |
| **ChatToolsSpec** | toolsSpec(2963–3426) | ObjectMapper + 可选 4 服务（workspace/skill/mcp/terminal，null=不挂载） | `ArrayNode build()`（静态装配，无状态） |
| **UpstreamLlmClient** | streamUpstream、streamUpstreamResponses、logUpstreamRequest、applyExtraHeaders、providerExtraHeaders、baseBody、applyReasoningRequest、isOpenAiEffort、supportsReasoningEffort、messagesArray、messageReasoning、withLevel、stripTrailingSlash、friendlyUpstreamError、deepestMessage、withUpstreamHint、abbreviateForSse、Tokens/TokenSink | ObjectMapper、ProxyProperties、LlmProperties、ModelProviderService（仅 withLevel 需要） | `StreamTurnResult stream(ResolvedLlm, ObjectNode body, TokenSink sink)`；`record StreamTurnResult` |
| **ChatContextAssembler** | buildMessages、appendHistoryMessage、rebuildableToolSteps、toolResultText、wireCostOf、messageTokens、compactForRound、compactToolContent、recycleOldImages、requestOverheadTokens、systemPromptWith 的工作区/技能读取部分 | ObjectMapper、AgentWorkspaceService、AgentSkillService、TerminalService（tools spec 开销估算用常量） | `AssembledContext build(...)`、`compact(...)`、`recycle(...)`；WireMessage/SystemPromptResult 类型 |
| **ModelResolver** | resolveLlm ×2、resolveFromStore、effectiveReasoningLevel、configured ×4、generateSessionTitle ×2、cleanTitle、isReasoningEffortUnsupported、visionAllowed、visionKey、isVisionUnsupported、isGenericUpstream400、hasImagesInMessages、stripImagesFromMessages | LlmProperties、ModelProviderService、ModelCapabilityRegistry | `ResolvedLlm resolve(...)`、`boolean configured(...)`、`String generateSessionTitle(...)` |
| **ModelCapabilityRegistry**（小类，~60 行） | visionRejectedModels、effortRejectedModels、effortKey、visionKey、查询/记忆方法 | 无（进程内 ConcurrentHashMap.newKeySet） | `boolean visionRejected(ResolvedLlm)`、`void markVisionRejected(...)`、`boolean effortRejected(...)`、`markEffortRejected/clearEffortRejected(...)` |
| **ToolStepEmitter** | emitToolStep ×3、finishToolStep、backfillToolMessage ×2、parseArgs、normalizeArgs、buildApprovalRequest、withRawArgs、scrubArgsForLog、parseArgsSafe、defaultTitle、countLines、lastToolResult | ApprovalService、RiskClassifier（静态）、ObjectMapper、AgentWorkspaceService（回填图片用）、McpServerService | `void emit(...)`（步骤+审批+执行+回填一体） |

### 2.3 共享类型的去向

| 类型 | 去向 |
|---|---|
| `ResolvedLlm` | → ModelResolver（record 保持 public） |
| `ToolOutcome` | → ChatToolExecutor |
| `ParsedArgs` | → ToolStepEmitter |
| `StreamTurnResult` / `Tokens` / `TokenSink` | → UpstreamLlmClient |
| `ChatTurn` / `ChatEventConsumer` / `TokenUsage` | 留在 ChatOrchestrationService（对外契约） |
| `WireMessage` | → ChatContextAssembler（跨类处改为包私有） |
| `SystemPromptResult` | → ChatContextAssembler |

## 三、迁移策略（分四步，每步可独立编译 + E2E 验证）

### Step 1：ChatToolsSpec（最安全，先做）

- 纯装配逻辑、零状态、零依赖纠缠。把 `toolsSpec()` 的 11 段 `putObject` 链抽成 `ChatToolsSpec.build(objectMapper, workspaceService, skillService, mcpServerService, terminalService)`。
- 或进一步「数据化」：把每个工具的 name/description/parameters 做成静态构造方法（`sqlTool()`、`logTool()`…），`build()` 只做拼接——为后续做工具注册表留路。
- 验证：`ToolsSpecInjectionTest` 反射改指新类（或改为直接构造，去掉反射）；E2E 冒烟一轮带工具对话。

### Step 2：ChatToolExecutor + ToolStepEmitter

- `executeTool` 的 11 路 if → 私有方法 `execExecuteSql` / `execReadLogs` / ... + 一个 `switch` 分发。`emitToolStep` 依赖 executeTool 与 ParsedArgs，二者一起搬。
- 注意：`executeTool` 里的 `liveOutput` 回调、MCP 图片块透传（`ToolOutcome.images`）行为必须逐行保留。
- 验证：`ChatOrchestrationServiceTest` 的 `invokeExecute`/`invokeBounded` 反射改指新类；E2E 各工具至少打一次（execute_sql / read_service_logs / manage_workspace / run_command / 一个 MCP 工具）。

### Step 3：UpstreamLlmClient + ModelResolver + ModelCapabilityRegistry

- 两套 SSE 实现整体平移（这是机械动作，方法体零改动），`Tokens`/`TokenSink` 一起走。
- `ModelResolver` 拿走解析链；降级缓存抽为 `ModelCapabilityRegistry`，以 Spring 单例注入主循环 / ToolStepEmitter / UpstreamLlmClient 三处。
- `generateSessionTitle` 复用 `streamUpstream`，跨类调用改走 `UpstreamLlmClient`。
- 验证：`reasoningLevelInjectedPerModelFamily` / `modelSettingsParsingCoversRoundTrip` 测试；E2E 用两个不同协议渠道各跑一轮（openai + responses）。

### Step 4：ChatContextAssembler + 门面收尾

- 历史装配 + 预算整体平移；`lastRecycledImages`/`estimateTokensFreed` 统计字段随之搬家，主循环通过返回值读取。
- 门面只留：主循环、注入可见性（emitContextSteps）、对外 API。
- 验证：`ContextBudgetTest`、`historyRebuildsToolChainAsWireMessages` 等；E2E 长对话（多轮工具调用 + 触发压缩）跑一轮。

### 测试同步原则

- 项目约定「单测不写断言」，拆分期间**不新增断言**；测试的反射目标改指新类即可（如 `ChatOrchestrationService.class.getDeclaredMethod("scrubArgsForLog",...)` → `ToolStepEmitter.class.getDeclaredMethod(...)`）。
- 每步完成后跑：`mvn -pl services/agent-service test` + 前端 `pnpm exec tsc --noEmit`（无影响但保底）+ 一轮真实对话 E2E。

## 四、风险与对策

| 风险 | 对策 |
|---|---|
| 反射测试断链（6 个方法名） | 每步同步改测试的反射目标；Step 顺序从测试依赖最少的 toolsSpec 开始 |
| 可变状态搬家后行为漂移 | 降级缓存进 ModelCapabilityRegistry（单例，语义不变）；压缩统计字段随 ContextAssembler，主循环只读返回值 |
| 构造函数依赖太多 | 拆分后门面构造器可改为注入 7 个子组件（而非 16 个 client）；测试便捷构造器同步瘦身 |
| 大文件移动产生 diff 噪音 | 每步一个 commit；方法体零改写（纯剪切+可见性调整），评审看结构不看行 |
| `friendlyUpstreamError` 等被多类复用 | 先移入 UpstreamLlmClient，若 ContextAssembler 需要则提为包私有静态工具（或独立 `UpstreamErrors` 类） |

## 五、预期收益

- 单文件 4,197 → 门面 ~900 行；最大方法 562 → ~80 行（switch 分发 + 各 handler）。
- 「执行面」与「描述面」工具定义物理相邻（ChatToolsSpec / ChatToolExecutor 同包同名前缀），新增工具只动这两处。
- 两套协议实现独立成类后，responses 协议的测试/改动不再波及对话主循环。
- 图谱热点（扇出 84 的 executeTool）分散为每 handler ≤ 10 扇出。
