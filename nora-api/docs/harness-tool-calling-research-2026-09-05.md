# Agent Harness 工具调用机制调研 — Claude Code / Codex CLI / 业界模式

调研日期:2026-09-05。信息源:官方文档与工程博客、openai/codex 源码(main 分支 2026-09 快照)、Claude Code 泄露 prompt 快照与社区逆向(已标注可信级别)。

## 0. 一句话总结

三家(Claude Code、Codex CLI、业界框架)在工具调用上的共识:**模型只负责决策,harness 负责一切工程**——截断、错误措辞、权限、审批、循环熔断、上下文管理都在 harness 层强制;事件流是「item + 成对生命周期事件」结构;错误回填给模型让它自纠而不是中断循环。

---

## 1. Claude Code 关键机制

来源:官方文档 code.claude.com/docs(已验证)+ 泄露快照 github.com/asgeirtj/system_prompts_leaks + 社区逆向 github.com/jalehvesical21/claude-code-decompiled(标注「逆向」的可能随版本漂移)。

### 1.1 工具 schema 纪律
- 工具数克制在 ~20 个,新工具门槛极高;扩展靠渐进式披露(skills、ToolSearch 延迟加载)而非加工具。
- description 三层结构:是什么 → 行为规则与边界(bullet)→ 明确的「不要做」指令。
- 失败条件前置写进 description(Edit:「old_string 必须精确唯一匹配,否则失败,先剥掉 Read 的行号前缀」)。
- `additionalProperties: false`;required 最小化(Read 只必填 file_path)。
- 参数 description 带正反例且禁主观词(Bash description:「Never use words like 'complex' or 'risk'」)。
- 跨工具耦合写进 description(Write:「未 Read 就覆盖已存在文件会失败」)。

### 1.2 主循环与并行
- 单线程 async generator,while(true) 每轮一次 API 调用;退出原因显式枚举:completed / aborted_streaming / aborted_tools / max_turns / prompt_too_long / model_error / hook_stopped 等。
- 每个工具有 `isConcurrencySafe(input)`(默认 false,fail-closed):只读工具可彼此并行,写/执行类独占;流式路径下工具在模型还在输出时就开始执行;最大并发 10。
- **兄弟中止**:Bash 失败 abort 同批兄弟工具(隐式依赖);Read/WebFetch 失败不影响兄弟。

### 1.3 工具结果处理(数值)
| 场景 | 行为 |
|---|---|
| Bash 成功输出 | 内联上限 30,000 字符;超出→头部预览 + 落盘文件路径,模型用 Read 二次读 |
| Bash 失败输出 | 内联 ~10,000 字符,切头尾摘录,不给路径 |
| 退出码 1 特判 | grep/find/diff/git diff 等的退出码 1 = 有效结果(无匹配不是错误) |
| 超时 | 默认 120s 上限 600s;超时自动转后台并告知模型 |
| Read | cat -n 格式(行号+tab+内容),默认 2000 行,超限给 PARTIAL view 提示 |

- 另:Anthropic 官方博客确认 Claude Code 工具响应默认上限 25,000 tokens。
- 错误措辞 = 「发生了什么 + 具体出路」(「出现多次→提供更长 old_string 或设 replace_all: true」);权限拒绝告知模型「调整而非原样重试」。

### 1.4 system-reminder 通道
- harness 在生命周期事件(session start、tool_result 后、compact 后)静默注入 `<system-reminder>` 包裹的提醒:模型可见、用户 UI 不可见,享受接近 system prompt 的信任级。
- **分工原则:硬约束放工具层强制,软偏好用 reminder 轻推**。

### 1.5 Compaction(逆向,数值)
- 四层:snip 删旧低值消息(零 API 成本)→ microcompact 压单个超大工具结果 → context collapse 可逆归档 → auto-compact 全量摘要。
- 阈值:200K 窗口在 ~167K(83%)触发;摘要 9 段固定结构(含「All user messages」「Current Work」);连续失败 3 次熔断(历史上曾出现 3272 次循环压缩事故)。

### 1.6 权限系统
- 评估序固定:hooks → deny → ask → permission mode → allow → canUseTool 回调;bare-name deny 直接把工具从模型上下文移除。
- 模式:default / acceptEdits / plan(只读,编辑永不自动批)/ bypassPermissions / dontAsk(ask 降级为 deny,适合无头服务端)/ auto(分类器模型代批,连续 3 次拒绝降级回人工)。
- 权限 UI 数据驱动:Bash 的 description 参数就是审批卡片正文。

### 1.7 子代理
- 派发时机:「回答需要跨多文件阅读——派发出去,留下结论而不是文件转储」。
- 中间工具调用留在子代理自己的上下文,只有 final message 回传父代理;回传前做防注入扫描(伪造 system-reminder 标签被转义)。

---

## 2. Codex CLI (codex-rs) 关键机制

来源:openai/codex main 分支源码,文件路径为源码事实。

### 2.1 工具定义
- 两层:`tools` crate 的 ToolSpec 枚举(Function / Namespace / ToolSearch / WebSearch / Freeform)+ `core/src/tools/handlers/*_spec.rs` 的具体 schema。
- **内置工具都带 output_schema**(约束回填结构)。
- exec_command:`cmd`(唯一 required)、`yield_time_ms`(分段收割,默认 10s)、`max_output_tokens`(默认 10000)、**审批参数内嵌 schema**:`sandbox_permissions`(use_default/with_additional_permissions/require_escalated)+ `justification`(提权理由)+ `prefix_rule`(可复用审批前缀)。
- **write_stdin 长命令模式**:exec 返回 session_id(非空=进程还在跑),模型用 write_stdin 续写/轮询分段收割——不阻塞等命令跑完。
- update_plan:`{ explanation?, plan: [{step, status: pending|in_progress|completed}] }`,「至多一个 in_progress」只写在 description 靠模型自觉,服务端不做状态机校验;回 `"Plan updated", success=true`。
- apply_patch:Freeform 工具(grammar 约束,非 JSON 通道),自定义补丁格式——上下文锚点 `@@` 而非行号(免行号漂移)、免 JSON 转义、一次原子多文件;流式解析器在模型还在输出时就发 PatchApplyUpdated 让 UI 实时预览;shell 里检测到 apply_patch heredoc 会拦截走原生通道(防绕过)。

### 2.2 主循环与回填
- 外层 RegularTask(100 行):loop { run_turn; 有 terminal_error 或无 pending input 则 return }。
- run_turn(2881 行):pre-sampling compact → loop { 收割插话 → 冻结本轮可见工具/权限 → 采样 → 处理流事件;token 超限先 compact 再 continue }。
- **回填即历史**:FunctionCallOutput { call_id, output: {body, success} } 作为历史 item 追加,下一轮整包重发。
- **RespondToModel 模式**:路由/参数解析失败不中断 turn,伪造一条错误 output 回给模型让它自纠。
- 工具 future 在流式接收期间就推进 FuturesOrdered(并行执行),流结束后统一收割。
- exec 输出喂模型格式:`Exit code / Wall time / [Total output lines: N](仅截断时)/ Output: ...`。

### 2.3 审批 × 沙箱
- AskForApproval: untrusted / on-request(默认,旧 on-failure 已并入)/ granular(每类审批独立开关,关闭=自动拒绝)/ never;SandboxMode: read-only(默认)/ workspace-write / danger-full-access。
- 判定管线:「approval → select sandbox → attempt → retry with escalated sandbox on denial」;预检(策略禁止→直接拒)→ 沙箱内先跑 → 被拒且策略允许 → 带原因二次审批提权重跑。
- 审批缓存 ApprovalStore 按 (命令, cwd) 去重;ReviewDecision 含 **ApprovedForSession(本会话不再问)** 和 **Declined**。
- apply_patch 独立安全检查 assess_patch_safety:AutoApprove / AskUser / Reject;writable_roots 内再保护 .git/.codex(防 .git/hooks 提权)。

### 2.4 事件流(对 SSE 时间线最有价值)
- **双层设计**:规范 TurnItem(~18 种变体)× 两个生命周期事件 `ItemStarted { thread_id, turn_id, item, started_at_ms }` / `ItemCompleted { ..., started_at_ms, completed_at_ms }`——**时间戳由服务端成对盖章,前端免配对计时**;旧事件(ExecCommandBegin/End 等)从 TurnItem 派生,不双写。
- 终态三值:`completed | failed | declined`——**用户拒绝不是失败**,时间线与回填语义都区分。
- 每个执行一个 call_id 贯穿 begin/delta/end 配对;ExecCommandOutputDelta 分 stdout/stderr 流式滚动。
- TurnComplete 带 `duration_ms` 和 `time_to_first_token_ms`(TTFT 一等公民);TokenCount 带 total/last/cached tokens + model_context_window。
- TurnDiff(unified_diff)、ContextCompacted 都是时间线可见事件。

### 2.5 Compact 与 system prompt
- 触发:每 turn 采样前 + 每轮结束超限时自动 + 手动 /compact;本地实现 = 摘要作为最后一条 assistant 消息(SUMMARY_PREFIX 标记)+ 保留带注记用户消息 + 整体替换历史;压缩期间再超限→从最老 item 逐个删除重试。
- compact prompt 要点:「为另一个要接手任务的 LLM 写 handoff summary:进度与关键决策 / 重要上下文与约束 / 待办与下一步 / 继续所需关键数据」。
- system prompt:模型指令模板(80 行)+ personality 占位符;`<user_instructions>`(AGENTS.md)、`<environment_context>`(cwd/shell/日期/权限/subagents)等以 XML 标签作为历史 context item 注入,不拼进 system 字符串。

---

## 3. 业界模式与数值速查

来源:Anthropic 工程博客系列、OpenAI Agents SDK 文档与源码、Vercel AI SDK 5 协议、LangGraph/LangSmith/Langfuse 文档、MCP 规范 2025-06-18、Manus 博客。

### 3.1 循环控制
| 框架 | 默认上限 |
|---|---|
| OpenAI Agents SDK | max_turns = 10 |
| LangChain AgentExecutor | max_iterations = 15 |
| LangGraph | recursion_limit = 25 |
| Nora 现状 | 5 轮 |

- 循环检测三件套(praison.ai 模式):同 (tool,args) 指纹重复、轮询无进展(结果哈希不变;status/poll/wait 类工具豁免)、A→B 震荡;窗口 30,warn=10/critical=20。
- **软阻断两级**:先注入系统警告给一次自救,再犯把工具结果替换为 `{error, loop_blocked: true}`——不抛异常、不中断流。

### 3.2 工具结果与错误协议
- Claude Code 响应上限 25K tokens;OpenAI ToolOutputTrimmer(历史轮瘦身)max 500 chars + preview 200 + 保护最近 N 轮。
- 防ID幻觉:response_format concise|detailed 分层给 ID;返回「当前有效 ID 列表」让模型只能选;UUID → 语义名/短 ID。
- 错误三段式:`出了什么错 + 违反哪条约束 + 正确输入示例`;永远作为工具结果回传,不让异常穿透 SSE。
- Manus:**保留失败在上下文里**(「擦掉失败就擦掉了证据」)。

### 3.3 SSE 执行时间线协议(Vercel AI SDK 5 Data Stream Protocol,最完整公开协议)
工具生命周期 5 事件:`tool-input-start → tool-input-delta(参数增量)→ tool-input-available → tool-approval-request/response → tool-output-available | tool-output-denied`;`start-step/finish-step` 作轮次分隔;`error` 只有 errorText 字符串;`reset-step` 支持撤回;token 级(text-delta)与 step 级事件分离。
Trace 模型(LangSmith/Langfuse/OpenAI SDK 共识):trace=会话,span 树=LLM 调用/工具执行,parent_id 构树,LLM span 记 token 与模型名。

### 3.4 审批持久化(human-in-the-loop)
- OpenAI Agents SDK:`needs_approval=True` 或 callable(fail-closed);中断后 `state.to_json()` 把审批状态+sticky 决策+用量整体序列化跨进程恢复;`always_approve` 本 run 内不再问;拒绝文案可自定义并作为工具错误回给模型。
- LangGraph:`interrupt(payload)` 挂起 + checkpointer 写库,人工决定经 `Command(resume)` 成为 interrupt() 返回值;绑定 thread_id;节点从头重跑,副作用要幂等。
- MCP 规范:ToolAnnotations{readOnlyHint, destructiveHint, idempotentHint, openWorldHint} **默认全 false(默认假定有副作用)**;注解不可信;「权限由 harness 强制,不是模型」(Claude Code 原则)。
- DB 落地表建议:`approval_id, session_id, tool_call_id, tool_name, arguments, status(pending/approved/rejected), decided_by, always_for_run, created_at`。

### 3.5 KV-cache / 上下文工程(Manus 教训)
- **KV-cache 命中率是生产 agent 最重要的单一指标**(输入输出比 ~100:1,缓存价差 10 倍)。
- 上下文 append-only;序列化确定性(JSON key 排序);时间戳放消息尾部(开头=杀缓存)。
- **Mask, don't remove tools**:会话中增删工具毁缓存且引发幻觉动作;用状态机/logits mask 控制可用性;prompt caching 要求工具数组在缓存前缀第一段且绝对不变(tools→system→messages 顺序)。
- 压缩必须可还原:web 内容可丢,URL 要留;文件系统是终极上下文。
- todo.md 反复重写 = 把目标「背诵」到上下文末尾对抗注意力漂移(recitation)。
- 子代理即压缩器:子代理花数万 token 探索,回传 1,000–2,000 token 蒸馏摘要;大结果写外部存储只回传轻量引用。

---

## 4. 对照 Nora 的落地建议(按优先级)

Nora 现状:单线程 ReAct 最多 5 轮;SSE 事件 step(running/completed/failed)/delta/sources/done/error;step id `s-tool-N` / `s-tool-N-call-M`;Guardrail(SQL 仅单条 SELECT/SHOW/EXPLAIN、服务名白名单、limit 1–100);工具输出脱敏 + 8KB 截断;短期记忆取最近 6 条;审批协议已 spec 未实现。

> **2026-09-12 更新**:跨轮工具链重建已实现(见 `context-management-design.md`)——工具步骤持久化脱敏 rawArgs,历史装配时重放为 `assistant(tool_calls)+tool(result)` 对。此前的「只装配 content 纯文本」在实测中导致弱模型编造工具结果(deepseek-v4.1-flash 虚构 sandbox 拦截),属 P0 级缺陷,已修复。其余 P0/P1 项(截断对标、错误三段式、终态三值、done 计量、schema 纪律)此前已陆续落地。

### P0 — 直接可改(不动架构)
1. **截断对标**:8KB 统一截断改为「成功 30K 字符 / 失败 10K 字符头尾摘录」;超限时保留头部预览,落盘路径或给「用 read 工具/分页续读」的指针;SQL 结果已有 50 行限制,补截断标记字段(truncated: true)。
2. **错误三段式**:Guardrail 拒绝信息改为 `拒绝了什么 + 违反哪条规则 + 正确示例`(如「仅允许单条 SELECT;示例:SELECT * FROM t LIMIT 10」);工具失败回填模型而不是只推 step(failed)。
3. **终态三值**:step 状态加 `declined`,与 failed 区分——为审批协议预留,「用户拒绝」不是执行失败。
4. **done 事件补计量**:TurnComplete 模式,done 里带 duration_ms、time_to_first_token_ms、token usage(网关返回后)——前端上下文用量估算即可替换。
5. **schema 纪律自查**:execute_sql / read_service_logs 的 description 补「不要做」与失败条件前置;参数加例子;response_format 或 detail 参数按需。

### P1 — 审批协议实现时照抄
6. **审批内嵌 schema**:借鉴 Codex,工具参数加 `require_approval/justification` 类字段,模型请求敏感操作时自带理由,审批卡片直接渲染。
7. **服务端三段式**:预检(策略禁止→直接拒,不发审批)→ 沙箱/只读连接先试 → 被拒且策略允许→审批后提权重跑。
8. **审批缓存/sticky**:按 (操作前缀, 目标) 缓存;支持「本会话不再问」(ApprovedForSession 语义),存 sessionId 作用域。
9. **持久化表**:approval_id, session_id, step_id, tool_call_id, tool_name, arguments, status(pending/approved/rejected/decined), decided_by, always_for_run, created_at——与已 spec 的 approvalToken 绑定,模型文本中的「同意」不作数(现有 spec 已正确)。

### P1 — 事件流收敛(下轮前端时间线迭代)
10. **双层事件模型**:向 Codex 收敛——定义 TurnItem(id/type/业务字段)+ ItemStarted/ItemCompleted 成对事件,服务端盖章 started_at_ms/completed_at_ms;现有 s-tool-N 编号可映射为 item id,前端只消费一对事件按 type 分发。
11. **轮次边界**:引入 start-step/finish-step 语义(一次 LLM 调用=一轮),时间线按轮分组折叠,替代当前按 step id 前缀推断。

### P2 — 上下文与循环进化
12. **循环熔断**:max rounds 5 → 配置化(默认 10,复杂任务 25);加循环检测(同 (tool,args) 指纹 + 结果哈希,连续 warn→软阻断 loop_blocked)。
13. **分层记忆**:最近 6 条 → 「最近 N 轮完整 + 更早轮次清工具结果原文留摘要指针」(tool-result clearing 是最安全的第一步);compact 用 handoff 摘要模板(进度/决策/待办/关键数据),SUMMARY_PREFIX 标记。
14. **缓存友好**:同一会话内工具定义数组不变(为将来多工具+MCP 做准备);序列化 key 确定性;时间戳不入 prompt 开头。
15. **system-reminder 通道**:连续工具失败 ≥3 → 注入「stop and rethink」;token 超 80% → 警告;硬约束留工具层,软偏好走 reminder。

### 暂缓
- apply_patch 式自由格式工具(Nora 无文件编辑工具,SQL 通道 JSON 足够)。
- write_stdin 长命令分段收割(Nora 的 SQL 有超时与行数限制,暂无长驻进程;Docker 日志 SSE 已有独立通道)。
- 子代理编排(主线程优先)。

---

## 5. 来源索引

**官方一手**:anthropic.com/engineering(building-effective-agents / writing-tools-for-agents / code-execution-with-mcp / effective-context-engineering-for-ai-agents / multi-agent-research-system)· code.claude.com/docs(tools-reference / permissions / agent-sdk streaming / subagents)· platform.claude.com prompt-caching · developers.openai.com function-calling · openai.github.io/openai-agents-python(streaming / human_in_the_loop / tracing)· ai-sdk.dev/docs/ai-sdk-ui/stream-protocol · docs.langchain.com(langsmith run-data-format / langgraph interrupts)· langfuse.com/docs/observability/data-model · modelcontextprotocol.io spec 2025-06-18 · manus.im/blog(Context-Engineering)。

**源码**:github.com/openai/codex(codex-rs:tools/src/tool_spec.rs、core/src/session/turn.rs、core/src/tools/{handlers,sandboxing,orchestrator}、core/src/safety.rs、core/src/compact.rs、protocol/src/{protocol.rs,approvals.rs,items.rs,models.rs}、prompts/templates/compact/prompt.md、core/templates/model_instructions/gpt-5.2-codex_instructions_template.md)· github.com/openai/openai-agents-python(run_config.py DEFAULT_MAX_TURNS=10、extensions/tool_output_trimmer.py)。

**社区逆向(可能随版本漂移)**:github.com/asgeirtj/system_prompts_leaks · github.com/jalehvesical21/claude-code-decompiled · michaellvls.com system-reminders 分析 · github.com/Piebald-AI/claude-code-system-prompts · praison.ai doom-loop-detection · zylos.ai agent-harness-design-patterns。
