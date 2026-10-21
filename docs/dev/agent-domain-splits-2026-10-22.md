# Agent 域体量收敛(2026-10-22)

## 背景

代码审查结论:架构方向正确(单应用、领域分包、Controller 与 Agent 工具共用领域服务),
但 Agent 域集中了多个 600 行以上的类,职责混杂,改动时牵连面大。

知识图谱(重建后 8501 节点 / 34425 边)给出的复杂度热点:

| 方法 | 行数 | 圈复杂度 | 认知复杂度 |
|---|---|---|---|
| `ChatTurnRunner.runTurn` | 450 | 53 | 135 |
| `ToolStepEmitter.buildApprovalRequest` | 339 | 33 | 110 |
| `ChatOrchestrationService.chat` | 316 | 54 | 200 |

判断依据是职责而非行数:同一个类里如果两类改动互不相关(改审批文案 vs 改执行并发),
它们就该分开。

## 已完成的拆分

### 1. ToolPresentation(展示层)

`ToolStepEmitter`(1226 行)混着「这次调用看起来是什么」和「执行与否、如何记录」。

- 移出:参数解析(`parseArgs`)、标题派生(`derivedTitle`/`defaultTitle`/`actionLabel`)、
  审批载荷(`buildApprovalRequest`)、日志脱敏(`scrubArgsForLog`)、原始参数重放(`withRawArgs`)。
- 留下:`emitToolCalls`(并发与回填)、`emitToolStep`(循环检测、审批门、执行、终态)、
  参数指纹归一(`canonicalArgs`,属于执行去重而非展示)。
- 结果:ToolStepEmitter 1226 → 544 行,ToolPresentation 709 行。
- 边界:展示层不执行工具、不判权限、不写库;脱敏在离开本类前完成,步骤/审批卡/日志共用同一份文本。

### 2. WorkspaceBootstrap(引导注入)

`AgentWorkspaceService`(1297 行)同时是文件系统访问层和提示词装配层。

- 移出:SOUL/AGENTS/USER/MEMORY 的装配、逐文件与总量双预算、截断与缺失占位、
  memory/ 日记清单、项目内 `AGENTS.md`/`AGENTS.override.md` 向上收集。
- 留下:路径解析与安全(区内/区外)、读写删改、目录统计、`readBootstrap` 文件访问。
- 结果:AgentWorkspaceService 1297 → 1170 行,WorkspaceBootstrap 178 行。
- 调用方:`ChatContextAssembler` 与 `SessionProjectService` 改为注入 `WorkspaceBootstrap`。

### 3. TurnRetrieval(检索阶段)

`ChatOrchestrationService.chat` 前 50 行是检索,后 260 行是模型工具循环。

- 移出:是否检索的判定、`TOP_K`、引用与召回的合并顺序、降级步骤(`s-rag-degraded`)、
  引用统计步骤(`s-rag`)、检索范围解析 `scopeOf`。
- 结果:TurnRetrieval 106 行,编排方法减少约 50 行。
- 行为不变:检索是否发生、条数、引用优先于召回、降级文案均保持原样。

## 验证

| 检查 | 结果 |
|---|---|
| `mvn verify`(编译、302 个测试、Spotless) | 通过 |
| `python scripts/acceptance.py standards` | 11 项全部 PASS |
| 构建前停止持有 jar 的后端进程 | 已执行(`scripts/backend.ps1 -Action stop`) |

未执行:需要真实模型的 `core` / `live` / `builtins` 套件,本轮改动不涉及模型契约,
但拆分后未跑这三套。

## 尚未处理

按同一判断标准,以下类仍是多职责并存,留待后续:

- `ChatTurnRunner.runTurn`(450 行):会话初始化、事件装配、终态结算、
  标题生成混在一个方法里,且大量状态用单元素数组闭包传递。拆它需要先引入
  一个显式的「轮次状态」对象,否则只是把数组搬到另一个类。
- `ChatStoreService`(844 行):会话/消息/运行/步骤/检查点五组 SQL 同处一文件。
- `TerminalService`(744 行):命令执行、输出截断、终态持久化与恢复。
- `McpServerService`(915 行):注册管理、连接池、工具调用与结果转换。

这些属于结构性调整(涉及状态模型),按规范需要单独设计后再动,不并入本轮。
