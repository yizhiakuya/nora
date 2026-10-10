# Agent 与内置浏览器架构审查

日期：2026-10-05。审查对象：当前工作树中的 Agent 对话、事件流与恢复、内置浏览器、网页资料任务；包含相关未跟踪源码。

## 结论

现有分层可以继续沿用：对话只有一套模型—工具循环，浏览器运行、样本决定与原始证据已有不同职责，网页结果复用文件成果链路。当前最需要收敛的是**同一状态在多个地方各自维护，缺少一致的更新与提交规则**。它已经造成恢复流的交互卡失效、结果版本不跟随更新，以及历史分支与服务端不一致的路径。

本次未发现有足够证据定为 P1 阻断的问题；有四项 P2 行为风险、两项 P3 复杂度债。没有建议更换框架、另造任务执行器或整体重写。单线程 Playwright 驱动、四会话上限与重启不重放操作是已接受的设计边界。

## P2：有明确发生路径的问题

### 1. 普通流和恢复流分别更新同一消息，交互卡的终态规则已经分叉

性质：已有技术债；本轮扩展 commentary/answer 又在两条路径分别增加处理，继续放大同步成本。

证据：

- [agentApi.ts:278](D:/claude/Nora/nora-web/src/lib/api/agentApi.ts:278) 在同 stepId 再出现时清除审批；284–287 在提问步骤结算时清除提问；355–358 在 done 时清除二者。
- [useChat.ts:249](D:/claude/Nora/nora-web/src/hooks/useChat.ts:249) 的恢复 onStep 只更新 steps/content，没有上述清理。295–300 可把回放中的旧票据重新挂上；[useChat.ts:301](D:/claude/Nora/nora-web/src/hooks/useChat.ts:301) 的 done/error 也没有清票据，取消分支明确不拉权威历史。
- 后端真实顺序可由 [ToolStepEmitter.java:219](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ToolStepEmitter.java:219) 与235–238核对：question_required → 等待回答 → 同 stepId completed。审批批准后同样重发 running，见271–272。
- [QuestionService.java:72](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/QuestionService.java:72) 已消费票据即删除；[QuestionCard.tsx:30](D:/claude/Nora/nora-web/src/components/chat/QuestionCard.tsx:30) 提交失败只复位按钮，没有向用户解释票据已失效。

发生路径与后果：用户在等待期间刷新，恢复连接回放已处理的提问/审批，然后 Agent 继续执行。主流会撤卡，恢复流仍可显示旧卡；提问超时或恢复轮被停止时尤其明显。重新提交的是已消费票据。正常完成后的历史回读通常能清掉，但在下一步执行、取消终态和历史请求失败时不能依赖它。

最小改法：保留 fetch SSE 与 EventSource 两种传输，提取一个纯函数更新 ChatMessage，统一 step/delta/reasoning/票据/done/error 的合并规则；先复用现有 normalizeStep、appendRoundText。接续句柄与组件生命周期仍留在 useChat，不另建全局事件总线。同时把 question 与 approval 都排除出本地历史缓存，现有154行只排除了 approval。

建议实测：等待提问时刷新，再回答/超时/停止；在另一客户端批准后回到本会话；与不刷新路径比较交互卡和步骤终态。

### 2. 结果弹窗把 latestReceipt 复制为本地状态，却没有跟随服务端的新版本

性质：本轮新增功能风险。

证据：

- [useBrowserResults.ts:10](D:/claude/Nora/nora-web/src/hooks/useBrowserResults.ts:10) 仅用 task.latestReceipt 初始化 receipt；18行按这个本地 receipt 请求行。26行 effect 依赖没有 latestReceipt 的变化；49行只在两个 id 相等时更新对象。
- [BrowserTaskCard.tsx:52](D:/claude/Nora/nora-web/src/components/browser/BrowserTaskCard.tsx:52) 收集中也可打开结果；72行弹窗 key 是 task.id，新收据不重挂载。
- [BrowserResultsDialog.tsx:61](D:/claude/Nora/nora-web/src/components/browser/BrowserResultsDialog.tsx:61) 下载直接使用 hook 返回的 receipt；[useBrowserResults.ts:31](D:/claude/Nora/nora-web/src/hooks/useBrowserResults.ts:31) 保存也使用它的 id。后端 [BrowserTaskService.java:117](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserTaskService.java:117) 从该旧收据取 captureIds，生成的新版本仍只包含旧候选。

发生路径与后果：补采运行期间打开已有结果A，运行完成产生B，浏览器轮询更新任务卡而弹窗仍停在A。此时可下载旧表格；修改选行再保存还会用A生成版本C，忽略B新增的资料。第二个窗口完成补采也有同样路径。

最小改法：明确区分服务端已保存版本和本地选行草稿。新 receipt 到达且没有未保存选行时切换版本并重读；有草稿时保留草稿，提示已有新版本并阻止直接覆盖，提供刷新/重新应用选择入口。**不要用无条件 effect 覆盖用户正在编辑的选行**。保存失败继续保留草稿；保存成功再切换到返回的收据。

建议实测：保持结果弹窗打开，等待补采完成；分别在有、无未保存选行的情况下检查版本、下载内容和保存来源。

### 3. 重试与编辑重发只改变本地历史，服务端的追加/截断语义没有保持一致

性质：已有技术债，不是本轮样式或浏览器功能引入。

证据：

- [useChat.ts:544](D:/claude/Nora/nora-web/src/hooks/useChat.ts:544) 重试把失败助手原位替换，然后551行再次 runTurn 原用户内容，没有删除旧服务端轮次。
- [ChatTurnRunner.java:126](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java:126) 每次请求都保存一条新的用户消息，379行保存一条助手消息；[ChatStoreService.java:146](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatStoreService.java:146) 是 INSERT，不存在按本地助手 id 替换的契约。
- [useChat.ts:595](D:/claude/Nora/nora-web/src/hooks/useChat.ts:595) 编辑先裁本地数组，598–601吞掉截断失败，610行仍继续发送。saveMessages只写 Zustand 缓存，见[useChatSessions.ts:144](D:/claude/Nora/nora-web/src/hooks/useChatSessions.ts:144)；不存在注释声称的“下次本地写回覆盖旧服务端分支”。

发生路径与后果：已落库的失败轮被重试后，UI先显示原位替换，刷新却多出旧失败轮与重复用户消息。编辑遇到后端截断失败时，界面隐藏的旧分支仍会进模型上下文，后续历史同步又把它带回。按数组下标截断也因此依赖一个没有始终维护的前提。

最小改法：先明确重试是追加新轮还是替换旧轮。追加就让UI也追加并保留原失败；替换就先成功截断再发送。编辑截断失败时恢复本地旧历史、显示错误并停止发送。先修契约一致性，无需立即引入通用分支系统或更改全部消息模型。

建议实测：对一个已落库的失败轮重试并刷新；让截断请求实际失败，核对没有新请求被发出、旧历史和用户编辑稿仍可恢复。

### 4. 人工选行先提交排除项，再生成结果；后续失败会留下半次业务提交

性质：本轮新增功能风险。

证据：

- [BrowserTaskService.java:127](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserTaskService.java:127) 计算 excludedKeys，131行立即保存计划，135行才执行 export。
- [BrowserEvidenceService.java:136](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserEvidenceService.java:136) 后续依次写CSV、保存收据、verify、登记成果；任一步文件/数据库操作都可能抛出失败。117行的所有后续导出都会使用计划中已经保存的 excludedKeys。
- [useBrowserResults.ts:34](D:/claude/Nora/nora-web/src/hooks/useBrowserResults.ts:34) 对整个 adjust 失败显示错误，没有拿到新收据，也不知道计划已经改变。

发生路径与后果：保存选行时文件写入或收据存储失败，界面还保留旧已保存版本，但计划的排除项已变。之后补采/导出会应用一次界面认为失败的选择，任务、收据与文件不再表达同一次提交。

最小改法：先以候选排除集合生成并核验新文件，再在成功提交阶段事务性保存新收据和计划排除项；失败保留旧计划及旧收据为当前版本。可沿用现有版本目录处理未提交文件，不需要跨文件系统的分布式事务。需要收敛 adjust 与 export 的提交边界，而不是在前端补更多状态猜测。

建议实测：文件不可写/收据保存失败后核对旧计划仍生效，再正常重试；这条失败路径本次未做破坏性注入。

## P3：成本随业务数据增长的架构债

### 5. 轻量轮询在浏览器 actor 内重新加载证据，并反复哈希全部导出文件

性质：本轮新增代码的扩展成本；单线程驱动本身不是问题。

证据：[useBrowser.ts:46](D:/claude/Nora/nora-web/src/hooks/useBrowser.ts:46) 每1.5/2.5秒读状态。[BrowserService.java:59](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserService.java:59) 将整个 state 放入操作队列，260–262行加载 exports 和任务摘要。[BrowserEvidenceService.java:222](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserEvidenceService.java:222) 最多读取100个收据，227行逐个读完整文件计算 SHA；任务摘要还按 captureId 读取采集内容，见[BrowserEvidenceService.java:252](D:/claude/Nora/nora-api/services/agent-service/src/main/java/com/nora/agent/service/BrowserEvidenceService.java:252)。

后果：浏览器未展开时仍执行这些读操作；产物版本越多，刷新一次状态越贵，并与网页操作共用队列。一次耗时文件读取会拖住其他会话的截图/控制。这是由调用位置确定的阻塞路径，本次没有测量大版本量下的实际延迟，不宣称当前已出现性能事故。

最小改法：保持 Playwright 在原 actor 内，仅取标签、所有权和视口快照；把证据查询与文件检查放到 actor 外。普通轮询读取当前任务和所需收据，旧导出分页/按需读取；最新产物变更检查与显式 verify 继续保留，不能把证据核验删掉。

### 6. 每个流式增量都全量持久化所有会话缓存，恢复回声又需要专门守卫

性质：已有技术债；本轮更完整的过程步骤增加了缓存体积。

证据：[useChat.ts:152](D:/claude/Nora/nora-web/src/hooks/useChat.ts:152) messages每次变化就复制消息数组并调用 saveMessages；[useChatSessions.ts:150](D:/claude/Nora/nora-web/src/hooks/useChatSessions.ts:150) 更新整个 sessions，275行 persist未限定partialize。与此同时，169–178行再从store收编同一份消息，需要lastSavedRef、localWriteVersions及多个发送/接续守卫区分回声和权威更新。

后果：逐token更新会同步序列化包括其他会话在内的持久状态；成本跟全部缓存历史和工具结果大小增长。消息存在组件、store与后端三个形态，当前大量生命周期注释和守卫正是为了协调这些副本。这里只确认实现成本与更新路径，未做浏览器长历史性能基准。

最小改法：组件仍可保持即时流式状态，节流离线缓存写入，done/error/停止时立即flush；会话store区分历史快照和在途轮次，避免每个token写入持久历史后再读回。先缩小写入频率和范围，保留现有过期历史防护，不同时大改完整状态层。

## 推荐处理顺序

1. 先统一普通/恢复消息更新规则，补齐交互票据的生命周期。
2. 修复结果版本同步与保存来源；保留用户未保存草稿。
3. 修复重试/编辑的历史契约，以及选行调整的提交边界。
4. 在增加长期任务和更大采集量前，缩小轮询与持久缓存的全量工作。

后端编排已拆出工具定义、执行、步骤发射、上下文与上游客户端，方向可以继续保持。浏览器扩展仍需改动工具schema、风险分类、展示解析和执行接线，但本次未找到必须另造插件注册框架的证据；先修上述已有状态边界，比再增加一层抽象更有收益。

## 审查边界与验证口径

- 本次是 **bounded source review**，结合 git diff 与完整相关源码追踪，不是全仓审计。重点读取了 useChat/useChatSessions、普通与接续SSE、浏览器hooks/components/API、ChatTurnRunner、编排工具循环与上下文重建、BrowserService/Session/Task/Evidence。App路由、聊天容器和共享Modal用于核对生命周期与依赖边界；没有把最后的CSS修改当全部范围。
- 知识图谱按Verify tier检查入口，但本会话ALL_TOOLS没有search_graph、trace_path、get_code_snippet、query_graph、index_status或check_index_coverage工具。没有generation/覆盖证据，实际以定向源码和rg核对调用方；不声明图谱已完整覆盖。
- 浏览器服务实际位于agent-service，没有独立workspace-service。保留服务端样本确认、权限门、未知结果语义、原始证据与SHA核验，不建议为缩短代码删除它们。
- 已知门禁背景来自上一轮交付：前端typecheck/lint/119个冒烟测试/build、后端145个冒烟测试/Spotless/package，以及响应式和公开网站真实E2E已通过。本次没有重复执行门禁，也没有把这些成功泛化为本文失败路径已被验证。
- 发现1–4是当前源码可定位的行为/提交风险；复现步骤是建议补测的真实E2E，未声称本次动态复现。发现5–6是有代码路径证据的复杂度债，没有编造性能数值。用户要求单测仅冒烟是确定的项目偏好，不列为缺陷。
- 本次只新增此报告，没有修改产品代码、配置或用户数据，没有重启服务或提交代码。

## 后续处理

用户授权后，四项 P2 与两项 P3 已实施优化。具体变更、真实 E2E 验收及未覆盖的故障路径见 [状态与提交边界优化](../dev/agent-state-consistency-2026-10-05.md)；上文保留原始审查时的证据和边界。
