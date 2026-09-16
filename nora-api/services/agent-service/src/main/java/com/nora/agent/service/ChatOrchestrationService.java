package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.config.LlmProperties;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.dto.ApprovalRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Chat orchestration with an OpenAI function-calling agent loop:
 * <ol>
 *   <li>RAG retrieval (unchanged) → system prompt citations</li>
 *   <li>Tool loop: rounds where the model may call tools; each call emits a
 *       structured step (toolName + parsed input + typed result) and feeds
 *       the bounded output back to the model. The loop runs until the model
 *       stops calling tools (Codex/Claude Code semantics) — the round count
 *       is only a pathological-loop fuse (default 100), not a task budget;
 *       context growth is handled by compaction, and the user can stop
 *       at any time.</li>
 *   <li>Final answer streams token by token to the SSE consumer</li>
 * </ol>
 *
 * <p>Harness conventions applied (see docs/harness-tool-calling-research-2026-09-05.md):
 * tool results are bounded (30K chars success / 10K head+tail failure),
 * guardrail rejections follow a three-part error shape (what + why + how to
 * fix), terminal step status distinguishes {@code declined} (rule refusal)
 * from {@code failed} (execution error), and repeated identical calls are
 * soft-blocked with a hint instead of executing again.
 */
@Service
public class ChatOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);
    /** 上游 LLM SSE 事件时间线专用 logger(独立文件 agent-service-sse.log,见 logback) */
    private static final Logger sseLog = LoggerFactory.getLogger("com.nora.agent.sse");

    /**
     * 基础系统提示(harness 协议层,设计对齐 Hermes 的 stable 层哲学):
     * 只放跨任务、不可协商的协议约定;人格/风格/任务习惯全部下放到工作区文件
     * (SOUL.md 人格、AGENTS.md 约定、USER.md/MEMORY.md 记忆快照)——它们是 agent
     * 的可演化指令,编辑文件即改变后续行为,无需改代码。
     */
    private static final String SYSTEM_PROMPT = """
            你是 Nora 个人工作台中的 AI 助手。以下为协议约定(优先级最高,始终遵守):
            1. 引用知识库来源时必须使用 [[docName]] 标记(渲染协议)。
            2. 需要真实数据或执行操作时,直接调用相应工具——不要凭记忆编造,不要虚构工具执行结果或报错,也不要只描述计划而不行动;工具返回错误时如实说明。
            3. 你的语气、风格与工作习惯定义在下方工作区的 SOUL.md 与 AGENTS.md 中——它们是
               你的可演化指令:self-evolution 是预期行为,想调整行为方式时直接编辑对应文件。
            4. 用户说「记住…」时必须写入工作区文件落盘,不能只口头答应。
            5. 最终回答使用自然的 Markdown:段落紧凑、结论前置;不要复述工具参数或执行过程。
            6. 你是这个工作台的操作员:它包含对话/知识库/数据源/环境控制台/文件/自动任务/
               MCP/技能/设置九个功能面,你通过工具直接操作它们(查库、读日志、启停容器、
               跑命令、管数据源/技能/MCP)。用户问「你能做什么」或需要了解工作台功能时,
               先读技能「工作台使用手册」(manage_skill action=read)获取权威说明,不要凭记忆
               编造功能;不确定工作台某能力是否存在时,用工具查证而不是猜测。
            7. 工具可能返回图片附件(如相册图片)。当图片以图像附件形式提供时,
               直接基于你看到的内容回答;若当前模型不支持图像输入(图片会被跳过并附说明),
               如实告知“当前模型看不到图片”并建议切换到支持识图的模型,
               绝不要凭文件名或描述猜测图片内容。
            """;

    /**
     * 工具轮数保险丝（不是任务预算）。
     *
     * <p>语义对齐 Codex / Claude Code：工具循环跑到**模型自己不再调工具**为止；
     * 上下文增长由压缩（compactForRound）控制，用户随时可停。
     * 轮数上限只用于兜住病态死循环（如模型在两个工具间反复横跳且指纹去重未拦住），
     * 因此给一个远超正常任务需要的大值——实测"扫相册找猫"（50 张照片，
     * 拼图两页 + 48 次逐张细看）自然跑了 17 轮才收尾，旧的 10 轮默认值
     * 会把这类任务拦腰截断。
     */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 100;


    /** Repeats of the same (tool, args) fingerprint before the loop breaker trips. */
    private static final int LOOP_WARN_THRESHOLD = 2;
    private static final int LOOP_BLOCK_THRESHOLD = 3;

    private final LlmProperties llmProperties;
    private final RagRetrievalClient ragRetrievalClient;
    private final SqlToolClient sqlToolClient;
    private final ServiceLogClient serviceLogClient;
    private final ObjectMapper objectMapper;
    /** 出站代理配置(可为 disabled):外网 LLM 上游走代理,内网服务互调直连 */
    private final com.nora.common.http.ProxyProperties proxyProperties;
    private final ModelProviderService modelProviderService;
    private final ApprovalService approvalService;
    private final WriteSqlClient writeSqlClient;
    private final ContainerControlClient containerControlClient;
    private final DataSourceManageClient dataSourceManageClient;
    private final ServiceManageClient serviceManageClient;
    private final FileToolClient fileToolClient;
    private final McpServerService mcpServerService;
    /** Agent 工作区(文件系统上的私有空间;引导文件注入每轮上下文) */
    private final AgentWorkspaceService agentWorkspaceService;
    /** 指令型技能(目录注入 + 按需读全文) */
    private final AgentSkillService agentSkillService;
    /** 本机终端执行(run_command 工具;可空=测试构造器不接) */
    private final TerminalService terminalService;
    /** tools spec 装配器(从本类拆出,2026-09-17 复杂度审计 Step 1)。 */
    private final ChatToolsSpec toolsSpecBuilder;
    /** 工具执行器(从本类拆出,2026-09-17 复杂度审计 Step 2)。 */
    private final ChatToolExecutor toolExecutor;
    private final int maxToolRounds;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    ModelProviderService modelProviderService,
                                    ApprovalService approvalService,
                                    WriteSqlClient writeSqlClient,
                                    ContainerControlClient containerControlClient,
                                    DataSourceManageClient dataSourceManageClient,
                                    ServiceManageClient serviceManageClient,
                                    FileToolClient fileToolClient,
                                    McpServerService mcpServerService,
                                    AgentWorkspaceService agentWorkspaceService,
                                    AgentSkillService agentSkillService,
                                    TerminalService terminalService,
                                    @org.springframework.beans.factory.annotation.Value("${nora.agent.max-tool-rounds:100}") int maxToolRounds,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    com.nora.common.http.ProxyProperties proxyProperties) {
        this.llmProperties = llmProperties;
        this.ragRetrievalClient = ragRetrievalClient;
        this.sqlToolClient = sqlToolClient;
        this.serviceLogClient = serviceLogClient;
        this.objectMapper = objectMapper;
        this.modelProviderService = modelProviderService;
        this.approvalService = approvalService;
        this.writeSqlClient = writeSqlClient;
        this.containerControlClient = containerControlClient;
        this.dataSourceManageClient = dataSourceManageClient;
        this.serviceManageClient = serviceManageClient;
        this.fileToolClient = fileToolClient;
        this.mcpServerService = mcpServerService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.terminalService = terminalService;
        this.toolsSpecBuilder = new ChatToolsSpec(objectMapper, agentWorkspaceService, agentSkillService,
                mcpServerService, terminalService);
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.proxyProperties = proxyProperties != null ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();
        this.toolExecutor = new ChatToolExecutor(objectMapper, sqlToolClient, serviceLogClient, writeSqlClient,
                containerControlClient, dataSourceManageClient, serviceManageClient, fileToolClient,
                mcpServerService, terminalService, agentWorkspaceService, agentSkillService);
    }

    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null,
                null, null, null, null, null, null, null, null, null, null, DEFAULT_MAX_TOOL_ROUNDS, null);
    }

    /** Test entry: explicit max tool rounds, no provider store. */
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    int maxToolRounds) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null,
                null, null, null, null, null, null, null, null, null, null, maxToolRounds, null);
    }

    /**
     * Runs one chat turn: retrieval → tool loop → streaming answer.
     *
     * @param userMessage   the user's message text
     * @param history       prior turns of this session (oldest first)
     * @param eventConsumer receives step/delta/sources events as they happen
     * @return future completed with the full answer text once the stream ends
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, List.of(), null, eventConsumer);
    }

    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, null, eventConsumer);
    }

    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, null, eventConsumer);
    }

    /**
     * @param requestedReasoningLevel 对话框选择的思考等级;null = 用该模型在设置页配置的默认等级
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                PermissionMode.ASSIST, eventConsumer);
    }

    /**
     * @param permissionMode 审批档位(ASK 每次询问 / ASSIST 高风险询问 / FULL 全放行)
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                permissionMode, null, eventConsumer);
    }

    /**
     * @param sessionId 会话 ID(审批请求绑定用;null = 不启用审批门,全部自动执行)
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            String sessionId,
                                            ChatEventConsumer eventConsumer) {
        return chat(userMessage, history, reflections, requestedModel, requestedReasoningLevel,
                permissionMode, sessionId, null, eventConsumer);
    }

    /**
     * @param sessionId 会话 ID(审批请求绑定用;null = 不启用审批门,全部自动执行)
     * @param providerId 前端选定的模型服务商(渠道)id;同名模型跨渠道时精确定位,null = 按模型名解析
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<String> reflections,
                                            String requestedModel,
                                            String requestedReasoningLevel,
                                            PermissionMode permissionMode,
                                            String sessionId,
                                            Long providerId,
                                            ChatEventConsumer eventConsumer) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (userMessage.length() > 8000) {
            throw new IllegalArgumentException("message exceeds 8000 characters");
        }
        if (!configured(requestedModel, providerId)) {
            eventConsumer.step(new ChatStepDto("s-config", "think", "模型未配置",
                    "请先在设置中心配置 LLM API Key 和端点", 0L, "failed"));
            String message = "当前尚未配置可用的模型。请到设置中心配置 LLM 服务商、端点和 API Key 后重试。";
            eventConsumer.delta(message);
            return CompletableFuture.completedFuture(new ChatTurn(message, List.of(), null, null, null, null));
        }
        // 请求级等级优先,其次设置页为该模型配置的默认等级(在 resolveLlm 内合并)
        // Step 1: knowledge retrieval (best-effort, before the LLM call)
        long retrievalStart = System.currentTimeMillis();
        List<CitationDto> citations = ragRetrievalClient.search(userMessage, 6);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        if (!citations.isEmpty()) {
            eventConsumer.step(new ChatStepDto(
                    "s-rag", "tool", "检索知识库",
                    "召回 " + citations.size() + " 个相关片段（" + citations.get(0).docName() + " 等）",
                    retrievalMs, "completed", null, null, null, 0));
            eventConsumer.sources(citations);
        }

        // Step 2: tool loop — every round is a real streaming request.
        // content/reasoning deltas go to the client token-by-token as they arrive
        // from upstream; tool_call argument fragments accumulate locally and the
        // tools execute after the stream closes.
        // 先解析一次拿到合并后的思考等级(请求级 > 设置页该模型默认),工具轮与最终回答共用
        ResolvedLlm resolved = resolveLlm(requestedModel, requestedReasoningLevel, providerId);
        if (resolved == null) {
            // configured() 已校验过,这里防御性兜底
            throw new IllegalStateException("no LLM provider resolved for model: " + requestedModel);
        }
        // 系统性上下文预算(设计见 docs/context-management-design.md):
        // 会话级裁剪 + 轮内微压缩 + 超限恢复,统一走 ContextBudget
        ContextBudget budget = new ContextBudget(resolved.contextWindow());
        SystemPromptResult[] promptOut = new SystemPromptResult[1];
        List<WireMessage> messages = buildMessages(userMessage, history, citations, reflections, budget, promptOut);
        // 注入可见性(dsh 模式):把本轮注入的记忆/技能清单作为步骤下发,
        // 前端时间线可展开查看真实注入内容;无注入时静默跳过。
        // 注入本身每轮都发生(系统提示必须随每次请求携带),但步骤仅在首轮或
        // 内容较上次下发有变化时下发——不变的轮次不再重复展示(降噪,2026-09-12)
        emitContextSteps(promptOut[0], history, eventConsumer);
        final String reasoningLevel = resolved.effectiveReasoningLevel();
        String toolOutcome = null;
        final int[] roundsUsed = {0};
        final int[] lastPromptEstimate = {0};
        // TTFT:首个模型 token 到达耗时(对齐 harness TurnComplete 的 time_to_first_token_ms)
        final long turnStartMs = System.currentTimeMillis();
        final long[] ttftMs = {-1};
        TokenUsage totalUsage = null;
        // loop breaker state: (toolName + normalized args) → consecutive repeat count
        Map<String, Integer> callFingerprints = new HashMap<>();
        try {
            for (int round = 0; round < maxToolRounds; round++) {
                if (Thread.currentThread().isInterrupted()) {
                    // 工具执行/审批等待期间被取消:不再发起新的上游请求,按取消收场
                    log.info("tool loop interrupted (user cancel) before round {}, abort turn", round + 1);
                    return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                }
                roundsUsed[0] = round + 1;
                long overhead = requestOverheadTokens();
                int compactedCount = compactForRound(messages, budget, false, overhead);
                if (compactedCount > 0 || lastRecycledImages > 0) {
                    StringBuilder what = new StringBuilder();
                    if (compactedCount > 0) what.append("已压缩 ").append(compactedCount).append(" 条早期工具结果");
                    if (lastRecycledImages > 0) {
                        if (what.length() > 0) what.append("、");
                        what.append("已回收 ").append(lastRecycledImages).append(" 条消息的早期图片附件");
                    }
                    eventConsumer.step(new ChatStepDto("s-compact-" + round, "think",
                            "整理上下文", what + (compactedCount > 0 ? ",释放约 " + estimateTokensFreed + " tokens 预算" : ""),
                            0L, "completed", null, null, null, round));
                }
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                        + (int) overhead;
                StreamTurnResult result = streamTurn(messages, requestedModel, reasoningLevel,
                        round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                if (result.usage() != null) {
                    totalUsage = totalUsage == null ? result.usage() : totalUsage.add(result.usage());
                }
                if (result.failed) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 取消:中断被上游阻塞读转成 failed 结果(streamUpstream 已恢复标志),
                        // 这里短路——绝不当「空响应」重试,否则取消变成多烧一整轮 token
                        log.info("tool round interrupted (user cancel), abort turn");
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                    }
                    StreamTurnResult retry = null;
                    boolean explicitVisionReject = isVisionUnsupported(result.errorMessage);
                    boolean maybeVisionReject = !explicitVisionReject
                            && hasImagesInMessages(messages) && isGenericUpstream400(result.errorMessage);
                    if (explicitVisionReject || maybeVisionReject) {
                        // 模型不支持识图(上游拒绝图片):剥离图片后重试。
                        // 中转流式下的 400 只有模糊文案(maybeVisionReject),因此以“剥图后重试是否成功”
                        // 作为最终判据:成功才记住结论,后续轮次直接不再附图(“不支持就不去看”)。
                        int stripped = stripImagesFromMessages(messages);
                        log.info("vision retry for {} ({}): stripped {} image(s), explicit={}",
                                resolved.model(), resolved.baseUrl(), stripped, explicitVisionReject);
                        StreamTurnResult visionRetry = streamTurn(messages, requestedModel, reasoningLevel,
                                round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                        if (!visionRetry.failed) {
                            // 确认:这个模型确实看不了图(下一次直接不发图片)
                            visionRejectedModels.add(visionKey(resolved));
                            eventConsumer.step(new ChatStepDto("s-vision-" + round, "think", "模型不支持识图",
                                    "已自动跳过图片内容继续回答（可在设置中切换支持识图的模型）",
                                    0L, "completed", null, null, null, round + 1));
                        }
                        retry = visionRetry;
                    } else if (isContextOverflow(result.errorMessage)) {
                        // 上下文超限:硬压缩到恢复线(窗口 60%)后重试一次
                        compactForRound(messages, budget, true, overhead);
                        lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                                + (int) overhead;
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                    } else if (isReasoningEffortUnsupported(result.errorMessage)
                            && reasoningLevel != null && !reasoningLevel.isBlank()) {
                        // 上游不认这个档位(gemini-3.5-flash 等报 invalid_reasoning_effort):
                        // 剥掉档位重试一次——思考等级是增强项,不该让整轮对话失败。
                        // 重试期间同时挂上「该档位被拒」与「该模型不注入档位」两条结论:
                        // 后者让重试真正不带 reasoning_effort(传 null 档位时 gpt-5 家族
                        // 会回落注入 medium,只挂前者重试就不是"剥掉");
                        // 重试成功才保留结论(后续同档位请求不再撞 400,且换成别的档位
                        // 仍会正常尝试注入);失败则撤销(瞬时错误不拉黑)。
                        String rejectedKey = effortKey(resolved);
                        String stripKey = effortKey(withLevel(resolved, null));
                        effortRejectedModels.add(rejectedKey);
                        effortRejectedModels.add(stripKey);
                        log.info("reasoning_effort rejected by {} ({}), retrying without it",
                                resolved.model(), resolved.baseUrl());
                        retry = streamTurn(messages, requestedModel, null, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                        if (!retry.failed) {
                            eventConsumer.step(new ChatStepDto("s-effort-" + round, "think", "模型不支持该思考等级",
                                    "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
                                    0L, "completed", null, null, null, round + 1));
                        } else {
                            effortRejectedModels.remove(rejectedKey);
                            effortRejectedModels.remove(stripKey);
                        }
                    } else if (result.content().isEmpty() && result.toolCalls().isEmpty()) {
                        // 中转渠道偶发 4xx/5xx(无内容返回):自动重试一次,重试仍失败才放弃本轮。
                        // 已有部分内容/工具调用的失败不重试(内容已随流转发,重发会重复输出)
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                    }
                    if (retry != null) {
                        if (retry.usage() != null) {
                            totalUsage = totalUsage == null ? retry.usage() : totalUsage.add(retry.usage());
                        }
                        if (!retry.failed) {
                            result = retry;
                        }
                    }
                }
                if (result.failed) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 流中取消(已转发部分内容):不发 s-error step,直接按取消收场
                        log.info("tool round interrupted (user cancel) with partial content, abort turn");
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                    }
                    eventConsumer.step(new ChatStepDto("s-error", "think", "模型返回空响应",
                            result.errorMessage != null ? result.errorMessage : "上游未返回内容,请重试",
                            null, "failed", null, null, null, round + 1));
                    break;
                }
                if (result.toolCalls.isEmpty()) {
                    // 回答(与推理)已随流逐 token 转发完毕
                    // 校准用**当轮** usage:传累计值会与单轮估算相除产生虚高比值
                    logCalibration(lastPromptEstimate[0], result.usage(), budget);
                    return CompletableFuture.completedFuture(new ChatTurn(result.content, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
                }
                messages.add(new WireMessage(result.assistantMessage));
                for (JsonNode call : result.toolCalls) {
                    String callId = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String args = call.path("function").path("arguments").asText("{}");
                    String toolStepId = "s-call-" + callFingerprints.size() + "-" + callId;
                    emitToolStep(toolStepId, name, args, callFingerprints, messages, callId,
                            round + 1, permissionMode, sessionId, resolved, eventConsumer);
                    String lastResult = lastToolResult(messages);
                    if (lastResult != null) {
                        toolOutcome = lastResult;
                    }
                }
            }
        } catch (Exception e) {
            // 取消穿透工具循环(审批等待 join 被中断抛 CompletionException(InterruptedException)
            // 且消费掉标志;或流内异常带中断 cause):不回落强制回答,直接按取消收场
            if (isInterruption(e) || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                log.info("tool loop interrupted (user cancel), abort turn");
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
            }
            log.warn("tool loop failed, falling back to plain answer", e);
        }
        if (Thread.currentThread().isInterrupted()) {
            // s-error break / 轮次自然耗尽后取消:同样不发最终回答请求
            log.info("turn interrupted (user cancel) before final answer, abort turn");
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
        }

        // Step 3: forced-answer streaming round (with tool outcome already in the messages)
        long answerStart = System.currentTimeMillis();
        final String fallbackOutcome = toolOutcome;

        final int reasoningRound = roundsUsed[0] + 1;
        long answerOverhead = requestOverheadTokens();
        lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                + (int) answerOverhead;
        StreamTurnResult finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
        // 瞬时上游错误(空内容失败)自动重试一次;超限先硬压缩再重试。
        // 线程被中断(用户取消)绝不重试——那会让取消多烧一整轮上游 token
        if (finalResult.failed && finalResult.content().isBlank() && !Thread.currentThread().isInterrupted()) {
            if (isContextOverflow(finalResult.errorMessage)) {
                compactForRound(messages, budget, true, answerOverhead);
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                        + (int) answerOverhead;
                finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
            } else if (isReasoningEffortUnsupported(finalResult.errorMessage)
                    && reasoningLevel != null && !reasoningLevel.isBlank()) {
                // 工具循环在首个上游请求前异常退出(catch 兜底走强制回答)时,这一轮
                // 是本轮第一个上游请求:坏档位要在这里也能降级,否则整轮失败且每轮
                // 复现。与工具轮同款:挂两条结论(档位被拒 + 该模型不注入档位)让重试
                // 真正不带 reasoning_effort,重试成功才保留。
                String rejectedKey = effortKey(resolved);
                String stripKey = effortKey(withLevel(resolved, null));
                effortRejectedModels.add(rejectedKey);
                effortRejectedModels.add(stripKey);
                log.info("reasoning_effort rejected on final answer for {} ({}), retrying without it",
                        resolved.model(), resolved.baseUrl());
                finalResult = streamFinalAnswer(messages, requestedModel, null,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
                if (!finalResult.failed) {
                    eventConsumer.step(new ChatStepDto("s-effort-final", "think", "模型不支持该思考等级",
                            "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
                            0L, "completed", null, null, null, reasoningRound));
                } else {
                    effortRejectedModels.remove(rejectedKey);
                    effortRejectedModels.remove(stripKey);
                }
            } else {
                finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
            }
        }
        if (!finalResult.failed) {
            String answerText = finalResult.content;
            if (finalResult.usage() != null) {
                totalUsage = totalUsage == null ? finalResult.usage() : totalUsage.add(finalResult.usage());
            }
            if (!answerText.isBlank()) {
                logCalibration(lastPromptEstimate[0], finalResult.usage(), budget);
                return CompletableFuture.completedFuture(new ChatTurn(answerText, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            // 取消:不再用最后一次工具结果兜底(那会把取消轮标成正常完成、
            // 落库完整内容,与前端「停止生成」的半截气泡错位)
            log.info("turn interrupted (user cancel) at final fallback, abort turn");
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
        }
        // 流式最终回答失败:回落到最后一次工具结果作为回答,而不是死流
        String fallback = fallbackOutcome != null && !fallbackOutcome.isBlank()
                ? "查询结果：\n```\n" + Texts.abbreviate(fallbackOutcome, ChatToolExecutor.MAX_FAILURE_CHARS) + "\n```"
                : "";
        if (!fallback.isBlank()) {
            eventConsumer.delta(fallback);
            return CompletableFuture.completedFuture(new ChatTurn(fallback, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
        }
        eventConsumer.step(new ChatStepDto("s-error", "think", "模型调用失败",
                finalResult.errorMessage, System.currentTimeMillis() - answerStart, "failed",
                null, null, null, null));
        return CompletableFuture.failedFuture(
                new IllegalStateException(finalResult.errorMessage != null ? finalResult.errorMessage : "LLM stream failed"));
    }

    /**
     * Executes one tool call and emits its structured step pair
     * (running → terminal). Terminal status is {@code declined} when a
     * guardrail refuses the input (rule refusal, not an execution error),
     * {@code failed} when execution throws, {@code completed} otherwise.
     * ASK 档每次都请求批准;ASSIST 档仅高风险(RiskClassifier)请求批准;
     * FULL 档不询问。等待批准期间 step 保持 running,批准事件由 SSE 下发。
     */
    /** Legacy test/helper entry: no session means approval is bypassed. */
    private void emitToolStep(String toolStepId, String name, String args,
                              Map<String, Integer> fingerprints,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              ChatEventConsumer eventConsumer) {
        emitToolStep(toolStepId, name, args, fingerprints, messages, callId, roundIndex,
                PermissionMode.FULL, null, eventConsumer);
    }

    private void emitToolStep(String toolStepId, String name, String args,
                              Map<String, Integer> fingerprints,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              PermissionMode permissionMode,
                              String sessionId,
                              ChatEventConsumer eventConsumer) {
        emitToolStep(toolStepId, name, args, fingerprints, messages, callId, roundIndex,
                permissionMode, sessionId, null, eventConsumer);
    }

    /** Overload carrying the resolved LLM so image attachments can honour its vision capability. */
    private void emitToolStep(String toolStepId, String name, String args,
                              Map<String, Integer> fingerprints,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              PermissionMode permissionMode,
                              String sessionId,
                              ResolvedLlm llm,
                              ChatEventConsumer eventConsumer) {
        ParsedArgs parsed = parseArgs(name, args);
        // 跨轮历史重建(对齐 Claude Code/Codex「工具链即历史」):step 持久化脱敏后的
        // 原始参数,下一轮把它重放为 wire 层 assistant(tool_calls)+tool(result) 对——
        // 模型看到自己真实的调用记录,而不是"纯文本声称跑过命令"(实测:缺失工具链
        // 结构时弱模型会续写编造工具结果)。脱敏与日志同口径,不存凭据明文。
        ChatStepDto.StepInput input = withRawArgs(parsed.input(), args);
        // Claude Code pattern: the model fills the display title via the
        // description arg (imperative, no subjective words); fall back to
        // the tool name when it omits one
        String title = parsed.description() != null && !parsed.description().isBlank()
                ? parsed.description() : defaultTitle(name);
        long toolStart = System.currentTimeMillis();
        log.info("tool call: {} (round={}, args={})", name, roundIndex,
                Texts.abbreviate(scrubArgsForLog(args), 200));
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                null, null, "running", name, input, null, roundIndex));

        // loop breaker: same (tool, normalized args) repeated too often.
        // MCP 工具参数 schema 千差万别,typed input 抽不出共同字段——指纹直接用
        // 原始 args,避免不同参数被误判为重复调用;manage_workspace/manage_skill/
        // manage_mcp/run_command 同理(主体在 content/path/instructions/url/command 字段)。
        boolean rawFingerprint = name.startsWith("mcp__")
                || "manage_workspace".equals(name) || "manage_skill".equals(name)
                || "manage_mcp".equals(name) || "run_command".equals(name);
        String fingerprint = name + "|"
                + (rawFingerprint ? (args == null ? "" : args) : normalizeArgs(name, input));
        int repeats = fingerprints.merge(fingerprint, 1, Integer::sum);
        if (repeats > LOOP_BLOCK_THRESHOLD) {
            String error = "重复调用已阻断：同样的参数已连续执行 " + (repeats - 1)
                    + " 次且结果不变。请基于已有结果继续回答,或换一种查询/诊断方式。";
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(null, "重复调用被循环熔断阻断", null, null, false, error),
                    "declined", roundIndex, eventConsumer);
            backfillToolMessage(messages, callId, "ERROR: " + error);
            return;
        }
        if (repeats == LOOP_WARN_THRESHOLD) {
            log.info("loop warning: {} called {} times with identical args", name, repeats);
        }

        // 无会话通道闸(/agent/run 等 automation 场景):sessionId 为 null 意味着
        // 没有用户在场,审批门(APPWD/ASSIST 的询问)形同虚设——CRITICAL(不可逆/
        // 带外操作)在此通道一律拒绝;模型会收到引导文案转告调用方走聊天通道
        if (sessionId == null && RiskClassifier.classify(name, args) == RiskClassifier.Risk.CRITICAL) {
            String error = "拒绝执行：" + name + " 属于不可逆操作(删除/注册类),只能在有人值守的聊天对话中执行"
                    + "(用户需亲自批准)。请把这一结论连同操作目的返回给调用方";
            finishToolStep(toolStepId, name, title, input, toolStart,
                    new ChatStepDto.StepResult(null, "无人值守通道拒绝执行", null, null, false, error),
                    "declined", roundIndex, eventConsumer);
            backfillToolMessage(messages, callId, "ERROR: " + error);
            return;
        }

        // 审批门:ASK 全问;ASSIST 问 HIGH+CRITICAL;FULL 只问 CRITICAL
        // (不可逆/带外操作,如删数据源、注册纳管命令)。审批状态保存在
        // 服务端(ApprovalService),模型文本中的"同意"不构成批准
        if (approvalService != null && sessionId != null) {
            RiskClassifier.Risk risk = RiskClassifier.classify(name, args);
            boolean needApproval = permissionMode == PermissionMode.ASK
                    || permissionMode == PermissionMode.ASSIST && risk != RiskClassifier.Risk.LOW
                    || permissionMode == PermissionMode.FULL && risk == RiskClassifier.Risk.CRITICAL;
            if (needApproval) {
                ApprovalRequestDto request = buildApprovalRequest(toolStepId, name, parsed, permissionMode, args);
                ApprovalRequestDto ticket = approvalService.register(sessionId, toolStepId, request);
                eventConsumer.approvalRequired(ticket);
                boolean approved = approvalService.await(ticket.approvalToken());
                if (!approved) {
                    finishToolStep(toolStepId, name, title, input, toolStart,
                            new ChatStepDto.StepResult(null, "用户未批准", null, null, false,
                                    "用户未批准该操作,Agent 已跳过执行。如需执行请调整方案或让用户切换权限档位"),
                            "declined", roundIndex, eventConsumer);
                    backfillToolMessage(messages, callId,
                            "ERROR: 用户拒绝批准该操作。请说明操作目的,或改用无需写权限的方式回答");
                    return;
                }
                // 批准后重新发出 running(用户等待期间 step 可能显示为等待批准态)
                eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                        null, null, "running", name, input, null, roundIndex));
            }
        }

        ChatToolExecutor.ToolOutcome outcome = toolExecutor.executeTool(name, args, parsed, liveOutput -> {
            // 实时输出流(run_command):同 id step 原地替换,前端自然刷新
            // (与 reasoning_delta 同款机制);detail 放预览、status 保持 running
            eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                    liveOutput, null, "running", name, input, null, roundIndex));
        });
        boolean failure = outcome.content().startsWith("ERROR:");
        String status = failure ? "failed" : "completed";
        ChatStepDto.StepResult result = new ChatStepDto.StepResult(
                outcome.content(),
                outcome.summary(),
                outcome.rowCount(),
                countLines(outcome.content()),
                outcome.truncated(),
                failure ? outcome.content() : null);
        finishToolStep(toolStepId, name, title, input, toolStart, result, status, roundIndex, eventConsumer);
        backfillToolMessage(messages, callId, outcome.content(), outcome.images(), llm);
    }





    private void finishToolStep(String toolStepId, String name, String title, ChatStepDto.StepInput input,
                                long toolStart, ChatStepDto.StepResult result, String status,
                                int roundIndex,
                                ChatEventConsumer eventConsumer) {
        String summaryLine = result.summary() != null ? result.summary()
                : (result.error() != null ? Texts.abbreviate(result.error(), 160) : null);
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                summaryLine, System.currentTimeMillis() - toolStart, status, name, input, result, roundIndex));
    }

    /** Appends the tool result message fed back to the model (RespondToModel style: errors are content, not exceptions). */
    private void backfillToolMessage(List<WireMessage> messages, String callId, String content) {
        backfillToolMessage(messages, callId, content, java.util.List.of(), null);
    }

    /**
     * Tool-result backfill with optional image attachments.
     *
     * <p>When {@code images} is non-empty and the active model can see images, the
     * tool message carries a multimodal content array (text + image_url parts) —
     * verified end-to-end against the relay for both chat/completions and
     * responses protocols. Otherwise the images are dropped with an explicit note
     * so a text-only model never silently pretends to have seen them.
     *
     * @param visionOverride TRUE/FALSE = forced by settings; null = runtime adaptive
     */
    private void backfillToolMessage(List<WireMessage> messages, String callId, String content,
                                     java.util.List<McpServerService.McpToolResult.ImageBlock> images,
                                     ResolvedLlm llm) {
        ObjectNode toolMsg = objectMapper.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", callId);
        boolean wantImages = images != null && !images.isEmpty();
        boolean canSee = wantImages && visionAllowed(llm, images.size());
        if (!wantImages) {
            toolMsg.put("content", content);
        } else if (canSee) {
            ArrayNode parts = toolMsg.putArray("content");
            ObjectNode textPart = parts.addObject();
            textPart.put("type", "text");
            textPart.put("text", content);
            for (McpServerService.McpToolResult.ImageBlock img : images) {
                ObjectNode imgPart = parts.addObject();
                imgPart.put("type", "image_url");
                imgPart.putObject("image_url")
                        .put("url", "data:" + img.mimeType() + ";base64," + img.base64Data());
            }
        } else {
            // 模型不支持识图(或探测判定不可用):图片不参与上下文,明确告知而非静默丢弃
            toolMsg.put("content", content + "\n(注:本次工具返回了 " + images.size()
                    + " 张图片,但当前模型不支持图像输入,图片内容未提供;如需看图请在设置中改用支持识图的模型)");
        }
        messages.add(new WireMessage(toolMsg));
    }

    /**
     * 记忆“上游拒绝图片”的模型:首次遇到拒绝后,后续轮次直接不再附加图片,
     * 避免每次都要“失败→重试”白费一轮。进程内记忆(重启后重新探测)。
     */
    private final java.util.Set<String> visionRejectedModels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 记忆“上游拒绝 reasoning_effort”的模型:实测部分模型/渠道对未知档位直接 400
     * (gemini-3.5-flash 报 invalid_reasoning_effort:"the reasoning effort value
     * is not supported by the current model")。
     * 首次遇到后,该模型后续请求不再注入**该档位**,省掉“失败→重试”的一轮浪费。
     * 键含档位:上游拒绝的是具体取值,用户换成受支持的档位后仍会正常尝试注入。
     * 与 visionRejectedModels 同款:进程内记忆,重启后重新探测;且只在
     * 「剥档位重试成功」后才写入(瞬时错误不拉黑)。
     */
    private final java.util.Set<String> effortRejectedModels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 探测记忆的键:上游端点 + 模型 + 档位(上游拒绝的是具体取值,不能整模型拉黑)。 */
    private static String effortKey(ResolvedLlm llm) {
        return (llm.baseUrl() == null ? "" : llm.baseUrl()) + "|" + (llm.model() == null ? "" : llm.model())
                + "|" + (llm.effectiveReasoningLevel() == null ? "" : llm.effectiveReasoningLevel().toLowerCase());
    }

    /**
     * 上游是否明确拒绝 reasoning_effort 取值。
     * 实测文案(中转站透传上游错误):
     * "the reasoning effort value is not supported by the current model" /
     * "invalid_reasoning_effort" / "unsupported value ... reasoning_effort"
     */
    private static boolean isReasoningEffortUnsupported(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        if (!e.contains("reasoning")) return false;
        return e.contains("not supported") || e.contains("unsupported")
                || e.contains("invalid") || e.contains("not valid");
    }

    /**
     * 是否允许把图片附到该模型的请求里。
     * 优先级:设置页显式开关 > 运行时探测记忆 > 默认尝试(上游拒绝则自动剥离)。
     */
    private boolean visionAllowed(ResolvedLlm llm, int imageCount) {
        if (llm == null || imageCount <= 0) return false;
        if (Boolean.FALSE.equals(llm.vision())) return false;
        if (Boolean.TRUE.equals(llm.vision())) return true;
        return !visionRejectedModels.contains(visionKey(llm));
    }

    /** 探测记忆的键:同一上游端点上的同名模型共享结论。 */
    private static String visionKey(ResolvedLlm llm) {
        return (llm.baseUrl() == null ? "" : llm.baseUrl()) + "|" + (llm.model() == null ? "" : llm.model());
    }

    /**
     * 上游错误是否为“不支持图像输入”。
     * 实测拒绝文案示例:
     * "Model X does not support image input. Remove the image content or use a vision-capable model."
     */
    private static boolean isVisionUnsupported(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        return e.contains("does not support image") || e.contains("not support image")
                || e.contains("vision-capable") || e.contains("image input")
                || e.contains("invalid image") || e.contains("unsupported image");
    }

    /**
     * 请求里是否携带图片附件(多模态 tool 消息)。
     */
    private static boolean hasImagesInMessages(List<WireMessage> messages) {
        for (WireMessage m : messages) {
            JsonNode content = m.node().path("content");
            if (!content.isArray()) continue;
            for (JsonNode part : content) {
                if ("image_url".equals(part.path("type").asText())) return true;
            }
        }
        return false;
    }

    /**
     * 中转以流式返回时会把上游 400 粒度化为 "Upstream error: 400"——
     * 看不到“不支持图像输入”原文。因此当请求里带图且收到 400 时,
     * 按“可能是识图不支持”处理(剥图重试一次,成功则记住结论)。
     */
    private static boolean isGenericUpstream400(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        boolean has400 = e.contains("400");
        boolean vague = e.contains("upstream error") || e.contains("上游");
        return has400 && vague;
    }

    /** 从 messages 里剥离所有图片附件,返回被剥离的张数。 */
    private int stripImagesFromMessages(List<WireMessage> messages) {
        int stripped = 0;
        for (WireMessage m : messages) {
            ObjectNode n = m.node();
            if (!"tool".equals(n.path("role").asText())) continue;
            JsonNode content = n.path("content");
            if (!content.isArray()) continue;
            StringBuilder text = new StringBuilder();
            int imgs = 0;
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText("")).append("\n");
                } else if ("image_url".equals(part.path("type").asText())) {
                    imgs++;
                }
            }
            if (imgs == 0) continue;
            String body = text.toString().stripTrailing();
            n.put("content", body + "\n(注:本次工具返回了 " + imgs
                    + " 张图片,但当前模型不支持图像输入,图片内容未提供)");
            stripped += imgs;
        }
        return stripped;
    }

    /**
     * Extracts plain text from a tool message content node: either a plain string
     * (legacy) or a multimodal array (text + image_url parts, new). Images are
     * represented by a short marker so callers that only need text stay correct.
     */
    private static String textOfContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String type = part.path("type").asText("");
                if ("text".equals(type)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part.path("text").asText(""));
                } else if ("image_url".equals(type)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("(\u56fe\u7247\u9644\u4ef6)");
                }
            }
            return sb.toString();
        }
        return content.asText(null);
    }

    private String lastToolResult(List<WireMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            WireMessage m = messages.get(i);
            if ("tool".equals(m.node().path("role").asText())) {
                return textOfContent(m.node().path("content"));
            }
            if ("assistant".equals(m.node().path("role").asText())) {
                break;
            }
        }
        return null;
    }

    /** Line count is exposed for collapsed UI previews ("21 lines of output"). */
    private static Integer countLines(String content) {
        if (content == null) return null;
        return content.split("\n", -1).length;
    }

    /** Parsed tool call: typed input plus the model-written display title. */
    record ParsedArgs(ChatStepDto.StepInput input, String description, String containerAction,
                      /** manage_datasource / manage_service 的 action 子命令 */
                      String datasourceAction) {

        /** Back-compat constructor for call sites without the manage action. */
        ParsedArgs(ChatStepDto.StepInput input, String description, String containerAction) {
            this(input, description, containerAction, null);
        }
    }

    /** Parses tool arguments into the typed input shown by the frontend + the display title. */
    private ParsedArgs parseArgs(String toolName, String argsJson) {
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            String description = node.path("description").asText(null);
            if (description != null && description.length() > 120) {
                description = description.substring(0, 120);
            }
            String action = node.path("action").asText(null);
            switch (toolName) {
                case "execute_sql", "execute_write_sql" -> {
                    return new ParsedArgs(new ChatStepDto.StepInput(node.path("sql").asText(null), null, null,
                                    node.path("datasource").asText(null)),
                            description, null);
                }
                case "manage_datasource", "manage_service" -> {
                    String target = node.has("name") ? node.path("name").asText(null)
                            : node.has("id") ? node.path("id").asText(null)
                            : node.path("target").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, null, action);
                }
                case "read_file" -> {
                    String target = node.path("id").asText(null);
                    Integer limit = node.has("limit") && node.get("limit").isNumber()
                            ? node.get("limit").asInt() : null;
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, limit, target),
                            description, null, action);
                }
                case "manage_workspace" -> {
                    // 展示路径(根目录 list 无 path,用 dir 兜底)
                    String p1 = node.path("path").asText(null);
                    if (p1 == null) {
                        p1 = node.path("dir").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, p1),
                            description, null, action);
                }
                case "manage_skill", "manage_mcp" -> {
                    // read/update/remove 用 target(名称或 id);create/register 用 name
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("name").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, null, action);
                }
                case "run_command" -> {
                    // 命令原文放 target 字段(折叠行与审批卡都要完整可见);
                    // cwd 不进 typed input(避免误当"目标"显示)
                    String cmd = node.path("command").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, cmd),
                            description, null, action);
                }
                default -> {
                    if (node.has("action") && node.has("service")) {
                        return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), null),
                                description, node.path("action").asText(null));
                    }
                    if (node.has("service") || node.has("limit")) {
                        Integer limit = node.has("limit") && node.get("limit").isNumber()
                                ? node.get("limit").asInt() : null;
                        return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), limit),
                                description, null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), description, null);
                }
            }
        } catch (Exception e) {
            return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), null, null);
        }
    }

    /** Human title when the model omits the description arg. */
    private String defaultTitle(String name) {
        return switch (name) {
            case "execute_sql" -> "查询数据库";
            case "execute_write_sql" -> "写入数据库";
            case "read_service_logs" -> "读取服务日志";
            case "manage_container" -> "容器操作";
            case "manage_datasource" -> "数据源管理";
            case "manage_service" -> "服务纳管";
            case "read_file" -> "读取文件";
            case "manage_workspace" -> "工作区文件";
            case "manage_skill" -> "技能管理";
            case "manage_mcp" -> "MCP 服务器管理";
            case "run_command" -> "运行命令";
            default -> name.startsWith("mcp__") ? "调用 MCP 工具" : name;
        };
    }

    /** Stable string for loop detection: the meaningful part of the args. */
    private String normalizeArgs(String name, ChatStepDto.StepInput input) {
        if ("execute_sql".equals(name) || "execute_write_sql".equals(name)) {
            return (input.sql() == null ? "" : input.sql().trim().toLowerCase())
                    + "@" + (input.target() == null ? "" : input.target().toLowerCase());
        }
        if ("read_service_logs".equals(name)) return (input.service() == null ? "" : input.service()) + "#" + input.limit();
        if ("manage_container".equals(name)) return input.service() == null ? "" : input.service();
        if ("manage_datasource".equals(name) || "manage_service".equals(name)) {
            return (input.target() == null ? "?" : input.target().toLowerCase());
        }
        if ("read_file".equals(name)) {
            return input.target() == null ? "?" : input.target();
        }
        if ("manage_workspace".equals(name) || "manage_skill".equals(name) || "manage_mcp".equals(name)
                || "run_command".equals(name)) {
            return (input.target() == null ? "?" : input.target().toLowerCase())
                    + "#" + (input.limit() == null ? "" : input.limit());
        }
        return "";
    }

    /** approval_required 事件载荷:操作类型、目标、参数摘要、风险说明、参数明细。 */
    private ApprovalRequestDto buildApprovalRequest(String stepId, String toolName, ParsedArgs parsed,
                                                    PermissionMode mode, String rawArgs) {
        String actionType;
        String target;
        String risk;
        String detail = null;
        switch (toolName) {
            case "execute_write_sql" -> {
                actionType = "sql_write";
                target = parsed.input().target() != null
                        ? "数据源 " + parsed.input().target()
                        : "数据库(默认连接)";
                detail = "SQL: " + (parsed.input().sql() == null ? "(空)" : parsed.input().sql());
                risk = "将修改真实数据,不可自动撤销";
            }
            case "manage_container" -> {
                actionType = "container_control";
                target = parsed.input().service() == null ? "未知容器" : parsed.input().service();
                detail = "操作: " + parsed.containerAction();
                risk = "停止/重启容器会导致该服务短暂不可用";
            }
            case "manage_datasource" -> {
                actionType = "datasource_manage";
                String action = parsed.datasourceAction();
                target = parsed.input().target() == null ? "新数据源" : parsed.input().target();
                JsonNode a = parseArgsSafe(rawArgs);
                if ("remove".equals(action)) {
                    detail = "数据源: " + target + "\n后果: 连接记录 + 全部查询历史一并删除";
                    risk = "不可恢复的删除操作";
                } else if ("create".equals(action)) {
                    detail = "名称: " + a.path("name").asText("?")
                            + "\n类型: " + a.path("engine").asText("?")
                            + "\n地址: " + a.path("host").asText("?") + ":" + a.path("port").asInt(0)
                            + "\n数据库: " + a.path("database").asText("?")
                            + "\n用户: " + a.path("username").asText("(空)")
                            + "\n密码: (已隐藏,仅存入数据源服务)";
                    risk = "将新增一个数据库连接";
                } else {
                    risk = "对数据源连接执行 " + action + " 操作";
                }
            }
            case "manage_service" -> {
                actionType = "service_manage";
                String action = parsed.datasourceAction();
                target = parsed.input().target() == null ? "新纳管源" : parsed.input().target();
                JsonNode a = parseArgsSafe(rawArgs);
                if ("register".equals(action)) {
                    String kind = a.path("kind").asText("?");
                    detail = "名称: " + a.path("name").asText("?") + "\n类型: " + kind;
                    if ("FILE".equalsIgnoreCase(kind)) {
                        detail += "\n日志文件: " + a.path("fileLogPath").asText("?");
                    } else if ("DOCKER".equalsIgnoreCase(kind)) {
                        detail += "\n容器: " + a.path("containerName").asText("?");
                    } else {
                        detail += "\n启动命令: " + a.path("command").asText("?")
                                + "\n工作目录: " + a.path("workDir").asText("(无)");
                    }
                    risk = "PROC 源的命令将在宿主机被执行并被持续监控";
                } else if ("remove".equals(action)) {
                    detail = "纳管源: " + target + "(容器/文件本身不受影响)";
                    risk = "删除后该源不再被监控与采集日志";
                } else {
                    risk = "对纳管源执行 " + action + " 操作";
                }
            }
            case "manage_mcp" -> {
                actionType = "mcp_manage";
                // 与分类器/执行层同一归一化:别名 create/delete 等按 register/remove 展示明细
                String action = RiskClassifier.normalizeMcpAction(parsed.datasourceAction());
                JsonNode a = parseArgsSafe(rawArgs);
                if ("register".equals(action)) {
                    // 与执行层同一推断:有 command 无 url → STDIO(模型常省略 transport)
                    String transport = a.path("transport").asText("");
                    if (transport.isBlank() && !a.path("command").asText("").isBlank()
                            && a.path("url").asText("").isBlank()) {
                        transport = "STDIO";
                    } else if (transport.isBlank()) {
                        transport = "STREAMABLE";
                    }
                    target = a.path("name").asText("新 MCP 服务器");
                    JsonNode headers = a.path("headers");
                    StringBuilder headerKeys = new StringBuilder();
                    if (headers.isObject()) {
                        headers.fieldNames().forEachRemaining(k ->
                                headerKeys.append(headerKeys.length() > 0 ? ", " : "").append(k));
                    }
                    JsonNode env = a.path("env");
                    StringBuilder envKeys = new StringBuilder();
                    if (env.isObject()) {
                        env.fieldNames().forEachRemaining(k ->
                                envKeys.append(envKeys.length() > 0 ? ", " : "").append(k));
                    }
                    if ("STDIO".equalsIgnoreCase(transport)) {
                        // 本地进程:完整命令行给用户审(装的是什么包一眼可见)
                        StringBuilder cmdline = new StringBuilder(a.path("command").asText("?"));
                        if (a.path("args").isArray()) {
                            for (JsonNode n : a.path("args")) {
                                cmdline.append(' ').append(n.asText(""));
                            }
                        }
                        detail = "本地命令: " + cmdline
                                + (envKeys.length() > 0 ? "\n环境变量: " + envKeys + "(值已隐藏)" : "");
                        risk = "将在本机启动本地进程作为 MCP 服务器(进程有本机权限,其工具会挂载给 Agent 调用)";
                    } else {
                        detail = "名称: " + target
                                + "\n地址: " + a.path("url").asText("?")
                                + "\n传输: " + transport
                                + (headerKeys.length() > 0 ? "\n鉴权头: " + headerKeys + "(值已隐藏)" : "");
                        risk = "将注册外部 MCP 服务器:其工具会挂载给 Agent 调用(外部能力,执行范围未知)";
                    }
                } else if ("remove".equals(action)) {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    detail = "MCP 服务器: " + target + "\n后果: 注册记录与连接一并删除";
                    risk = "删除后其工具立即不可用,需重新注册才能恢复";
                } else {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    risk = "对 MCP 服务器执行 " + action + " 操作";
                }
            }
            case "run_command" -> {
                actionType = "terminal_command";
                JsonNode a = parseArgsSafe(rawArgs);
                String cmd = a.path("command").asText("?");
                String cwd = a.path("cwd").asText(null);
                Integer timeout = a.path("timeout").isInt() ? a.path("timeout").asInt() : null;
                String shell = a.path("shell").asText(null);
                // 命令原文完整展示(与 Claude Code 同款防线:用户审的就是将执行的)
                target = Texts.abbreviate(cmd, 60);
                detail = "命令: " + cmd
                        + (cwd != null ? "\n工作目录: " + cwd : "\n工作目录: 工作区根")
                        + (shell != null ? "\nShell: " + shell : "")
                        + (timeout != null ? "\n超时: " + timeout + "s" : "");
                risk = "命令将在本机以当前用户权限执行,可能有文件/网络副作用";
            }
            case "manage_workspace" -> {
                actionType = "workspace_file";
                JsonNode a = parseArgsSafe(rawArgs);
                String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction();
                String p = a.path("path").asText(null);
                if (p == null) {
                    p = a.path("dir").asText(null);
                }
                target = p == null ? "工作区" : p;
                detail = "操作: " + action + " | 路径: " + target
                        + (a.has("content")
                        ? " | 内容预览: " + Texts.abbreviate(a.path("content").asText(""), 200) : "");
                risk = "该路径在工作区之外——将改动本机的真实文件(不可自动撤销)";
            }
            default -> {
                if (toolName.startsWith("mcp__")) {
                    actionType = "mcp_tool";
                    // 畸形挂载名(LLM 幻觉出 mcp__srv 缺第二个 __)不能让 substring 越界
                    int sep = toolName.indexOf("__", "mcp__".length());
                    String serverName = sep < 0 ? "?" : toolName.substring("mcp__".length(), sep);
                    target = "MCP 服务器 " + serverName;
                    detail = "工具: " + toolName + "\n参数: " + Texts.abbreviate(rawArgs == null ? "{}" : rawArgs, 400);
                    risk = "外部 MCP 服务器提供的工具,能力未知,执行前需确认";
                } else {
                    actionType = toolName;
                    target = parsed.input().service() != null ? parsed.input().service()
                            : (parsed.input().sql() != null ? Texts.abbreviate(parsed.input().sql(), 40) : "—");
                    risk = "该档位下每次工具调用都需要确认";
                }
            }
        }
        return new ApprovalRequestDto(null, stepId, actionType, target,
                defaultTitle(toolName) + " · " + target, risk, detail);
    }

    /** 解析审批明细用的 args JSON;失败返回空节点(明细降级为不含参数)。 */
    private JsonNode parseArgsSafe(String rawArgs) {
        try {
            return objectMapper.readTree(rawArgs == null ? "{}" : rawArgs);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** Whether an LLM API key is configured. */
    public boolean configured() {
        return configured(null);
    }

    /**
     * 会话标题生成用的系统提示。
     *
     * <p>要点：限制长度（侧栏一行放得下）、强制单行、禁止包装（模型爱回
     * 「标题：xxx」或加引号）、禁止"关于…的讨论"这类无信息量的套话。
     */
    private static final String TITLE_SYSTEM_PROMPT = """
            你为对话生成一个简短标题。规则：
            1. 只输出标题本身，不要引号、不要「标题：」前缀、不要任何解释；
            2. 长度不超过 12 个汉字（或 24 个英文字符），必须单行；
            3. 用与用户消息相同的语言；
            4. 直接概括用户在做什么/问什么，不要写「关于…的讨论」「用户询问」这类套话；
            5. 不要以句号、问号等标点结尾。
            """;

    /** 送模型用于起标题的用户消息上限（字符）：标题只需开头意图，无需全文。 */
    private static final int TITLE_EXCERPT_CHARS = 600;

    /** 落库标题的最大长度：兜住模型不听话时的超长输出（DB 列宽 255）。 */
    private static final int TITLE_MAX_CHARS = 40;

    /**
     * 用一次轻量 LLM 调用为会话生成短标题（同步阻塞；调用方放到线程池里跑）。
     *
     * <p>复用主链路的上游调用（{@link #streamUpstream}），因此协议分歧
     * （openai / responses）、出站代理、供应商额外请求头都与对话完全一致——
     * 不另起一套 HTTP 逻辑。
     *
     * @return 清理后的标题；未配置模型或上游失败返回 null（调用方保留占位标题）
     */
    public String generateSessionTitle(String userMessage, String requestedModel) {
        return generateSessionTitle(userMessage, requestedModel, null);
    }

    /**
     * @param providerId 渠道 id(与对话同一解析口径,同名模型跨渠道时标题也走用户所选渠道)
     */
    public String generateSessionTitle(String userMessage, String requestedModel, Long providerId) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        // 标题是纯转写任务，不需要推理：显式压到最低档省钱省时
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel, null, providerId), "none");
        if (llm == null) {
            return null;
        }
        try {
            ObjectNode body = baseBody(true, llm);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", TITLE_SYSTEM_PROMPT);
            String excerpt = userMessage.length() <= TITLE_EXCERPT_CHARS
                    ? userMessage
                    : userMessage.substring(0, TITLE_EXCERPT_CHARS);
            messages.addObject().put("role", "user").put("content", excerpt);
            // 不带 tools：标题任务不允许调用工具
            StreamTurnResult result = streamUpstream(llm, body, (content, reasoning) -> {
            });
            if (result.failed()) {
                log.warn("session title generation failed: {}", result.errorMessage());
                return null;
            }
            return cleanTitle(result.content());
        } catch (Exception e) {
            log.warn("session title generation error: {}", e.toString());
            return null;
        }
    }

    /**
     * 清理模型输出的标题：折叠空白、剥掉包裹符号与「标题：」前缀、限长。
     *
     * <p>实测模型不会严格遵守格式：会给引号、写「标题：xxx」、甚至在标题后
     * 另起一行解释。这里统一清洗，避免脏标题直接进侧栏。
     */
    static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\\s+", " ").trim();
        // 只取第一行（模型可能补一段说明）
        if (t.isEmpty()) {
            return null;
        }
        // 剥前缀：标题：/ 会话标题：/ title:
        t = t.replaceAll("(?i)^(会话)?(标题|title)\\s*[:：]\\s*", "");
        // 剥两端包裹：引号、书名号、方括号、星号（markdown 粗体）
        t = t.replaceAll("^[\"'“”‘’《》\\[\\]【】*]+", "").replaceAll("[\"'“”‘’《》\\[\\]【】*]+$", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= TITLE_MAX_CHARS ? t : t.substring(0, TITLE_MAX_CHARS) + "…";
    }

    public boolean configured(String requestedModel) {
        return resolveLlm(requestedModel) != null;
    }

    /** Variant honouring an explicit provider id (渠道精确解析)。 */
    public boolean configured(String requestedModel, Long providerId) {
        return resolveLlm(requestedModel, null, providerId) != null;
    }

    private ResolvedLlm resolveLlm(String requestedModel) {
        return resolveLlm(requestedModel, null, null);
    }

    /**
     * 解析执行端点与思考等级:请求级等级 > 设置页该模型默认等级 > auto。
     * 该模型的 reasoningLevels 白名单同时约束请求级取值(不在白名单内则回落默认)。
     *
     * @param providerId 前端选定的渠道 id(同名模型跨渠道时精确定位);null = 按模型名解析
     */
    private ResolvedLlm resolveLlm(String requestedModel, String requestedReasoningLevel, Long providerId) {
        // 设置中心(数据库 provider store)优先:模型选择/思考等级/每模型协议都源于此。
        // 静态 nora.llm.* 配置仅作兜底(全新部署还没配 provider 时可用),
        // 否则环境变量一存在就会短路整个 provider 体系——UI 上怎么选模型都不生效。
        if (modelProviderService != null) {
            ResolvedLlm fromStore = resolveFromStore(requestedModel, requestedReasoningLevel, providerId);
            if (fromStore != null) return fromStore;
        }
        if (llmProperties.configured()) {
            return new ResolvedLlm(llmProperties.baseUrl(), llmProperties.apiKey(), llmProperties.model(), "openai",
                    null, null, null);
        }
        return null;
    }

    /** Provider-store leg of {@link #resolveLlm}; providerId 优先,缺失/失效时按模型名回落。 */
    private ResolvedLlm resolveFromStore(String requestedModel, String requestedReasoningLevel, Long providerId) {
        ModelProviderService.ActiveProvider provider = modelProviderService.activeProvider(providerId, requestedModel);
        if (provider == null || provider.endpoint() == null || provider.endpoint().isBlank()) return null;
        // 请求级模型名优先(activeProvider 已按它筛选供应商);仅在请求未指定时回落
        // 到该供应商模型列表的第一个。此前固定取 models.get(0),导致对话框里选的
        // 模型被静默替换成供应商第一个模型(如选 nemotron 实际跑 muse)。
        String model = requestedModel != null && !requestedModel.isBlank()
                && (provider.models() == null || provider.models().contains(requestedModel))
                ? requestedModel
                : (provider.models() == null || provider.models().isEmpty()
                        ? LlmProperties.DEFAULT_MODEL : provider.models().get(0));
        String effectiveLevel = effectiveReasoningLevel(provider, model, requestedReasoningLevel);
        // 协议按模型覆盖:modelSettings[model].protocol 优先,否则继承 provider 级协议
        ModelProviderService.PerModelSettings perModel =
                ModelProviderService.parseModelSettingsStatic(provider.modelSettingsJson()).forModel(model);
        String protocol = perModel.protocol() != null && !perModel.protocol().isBlank()
                ? perModel.protocol() : provider.protocol();
        return new ResolvedLlm(provider.endpoint(), provider.apiKey(), model, protocol, effectiveLevel,
                perModel.contextWindow(), perModel.vision());
    }

    /** Merges the per-request level with the per-model default from settings. */
    private String effectiveReasoningLevel(ModelProviderService.ActiveProvider provider, String model,
                                           String requestedLevel) {
        ModelProviderService.PerModelSettings settings =
                ModelProviderService.parseModelSettingsStatic(provider.modelSettingsJson()).forModel(model);
        List<String> whitelist = settings.reasoningLevels();
        boolean hasRequested = requestedLevel != null && !requestedLevel.isBlank() && !"auto".equalsIgnoreCase(requestedLevel);
        if (hasRequested && (whitelist.isEmpty() || whitelist.contains(requestedLevel))) {
            return requestedLevel;
        }
        String configured = settings.defaultReasoningLevel();
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)
                && (whitelist.isEmpty() || whitelist.contains(configured))) {
            return configured;
        }
        return null;
    }










    /** One streamed model turn: forwarded content/reasoning plus accumulated tool_calls. */
    record StreamTurnResult(boolean failed, String errorMessage, String content,
                            String reasoning, ObjectNode assistantMessage, List<JsonNode> toolCalls,
                            TokenUsage usage) {
    }

    /** Provider token accounting from one streamed turn (null fields when relay omits usage). */
    public record TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

        TokenUsage add(TokenUsage other) {
            if (other == null) return this;
            return new TokenUsage(
                    plus(inputTokens, other.inputTokens),
                    plus(outputTokens, other.outputTokens),
                    plus(totalTokens, other.totalTokens));
        }

        private static Integer plus(Integer a, Integer b) {
            if (a == null) return b;
            if (b == null) return a;
            return a + b;
        }
    }

    /**
     * One streaming model round. Upstream SSE is consumed line-by-line and every
     * content/reasoning token is forwarded to the client as it arrives (真流式);
     * tool_call argument fragments are accumulated locally and returned for
     * execution after the stream closes. Blocks the calling thread until the
     * upstream stream ends — run on a worker executor.
     */
    /** 中断识别:JDK HttpClient 阻塞读被 interrupt 时抛 IOException(cause=InterruptedException),逐层找。 */
    private static boolean isInterruption(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) return true;
            if (c.getCause() == c) break; // 自引用防环
        }
        return false;
    }

    private StreamTurnResult streamTurn(List<WireMessage> messages, String requestedModel,
                                        String reasoningLevel, int round,
                                        ChatEventConsumer eventConsumer,
                                        long[] ttftMs, long turnStartMs) {
        return streamTurn(messages, requestedModel, reasoningLevel, round, eventConsumer, ttftMs, turnStartMs, null);
    }

    private StreamTurnResult streamTurn(List<WireMessage> messages, String requestedModel,
                                        String reasoningLevel, int round,
                                        ChatEventConsumer eventConsumer,
                                        long[] ttftMs, long turnStartMs, Long providerId) {
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel, null, providerId), reasoningLevel);
        ObjectNode body = baseBody(true, llm);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        // 请求即将发出:刷新消费方的推理计时锚点(RAG/装配时间不计入思考时长)
        eventConsumer.upstreamRequestStarted();
        return streamUpstream(llm, body, (contentToken, reasoningToken) -> {
            if (ttftMs[0] < 0 && (contentToken != null || reasoningToken != null)) {
                ttftMs[0] = System.currentTimeMillis() - turnStartMs;
            }
            if (reasoningToken != null) eventConsumer.reasoningDelta(round, reasoningToken);
            if (contentToken != null) eventConsumer.delta(contentToken);
        });
    }

    /** Forced-answer variant of {@link #streamTurn} for the final round (tool_choice=none). */
    private StreamTurnResult streamFinalAnswer(List<WireMessage> messages, String requestedModel,
                                               String reasoningLevel, int round,
                                               ChatEventConsumer eventConsumer,
                                               long[] ttftMs, long turnStartMs) {
        return streamFinalAnswer(messages, requestedModel, reasoningLevel, round, eventConsumer, ttftMs, turnStartMs, null);
    }

    private StreamTurnResult streamFinalAnswer(List<WireMessage> messages, String requestedModel,
                                               String reasoningLevel, int round,
                                               ChatEventConsumer eventConsumer,
                                               long[] ttftMs, long turnStartMs, Long providerId) {
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel, null, providerId), reasoningLevel);
        ObjectNode body = baseBody(true, llm);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        // final round: the model must answer, not call more tools
        body.put("tool_choice", "none");
        eventConsumer.upstreamRequestStarted();
        return streamUpstream(llm, body, (contentToken, reasoningToken) -> {
            if (ttftMs[0] < 0 && (contentToken != null || reasoningToken != null)) {
                ttftMs[0] = System.currentTimeMillis() - turnStartMs;
            }
            if (reasoningToken != null) eventConsumer.reasoningDelta(round, reasoningToken);
            if (contentToken != null) eventConsumer.delta(contentToken);
        });
    }

    /** Pair of forwarded tokens for one SSE chunk. */
    private record Tokens(String content, String reasoning) {
    }

    private interface TokenSink {
        void accept(String content, String reasoning);
    }

    /**
     * Executes the request and relays upstream SSE chunks as they arrive.
     * Uses raw byte reading with incremental UTF-8 decoding (the relay can split
     * multi-byte characters across chunks — RestClient's string converter also
     * mangles text/event-stream to ISO-8859-1, browser-verified 2026-09-05).
     * Protocol dispatch: openai → POST /chat/completions (stream),
     * responses → POST /responses (stream) with event-name mapping.
     */
    private StreamTurnResult streamUpstream(ResolvedLlm llm, ObjectNode body, TokenSink sink) {
        logUpstreamRequest(llm, body);
        if ("responses".equalsIgnoreCase(llm.protocol())) {
            return streamUpstreamResponses(llm, body, sink);
        }
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // tool_calls accumulation: index → {id, name, args-builder} (fragments arrive out of order)
        Map<Integer, String> callIds = new HashMap<>();
        Map<Integer, String> callNames = new HashMap<>();
        Map<Integer, StringBuilder> callArgs = new HashMap<>();
        List<JsonNode> orderedCalls = new ArrayList<>();
        TokenUsage usage = null;

        try {
            java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder
                    .addressFor(llm.baseUrl());
            if (proxyAddr != null) {
                clientBuilder.proxy(java.net.ProxySelector.of(proxyAddr));
            }
            java.net.http.HttpClient client = clientBuilder.build();
            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(stripTrailingSlash(llm.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llm.apiKey())
                    .header("Accept", MediaType.ALL_VALUE);
            applyExtraHeaders(requestBuilder, llm);
            java.net.http.HttpRequest request = requestBuilder
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            sseLog.info("upstream POST {} -> HTTP {} (model={})",
                    stripTrailingSlash(llm.baseUrl()), response.statusCode(), llm.model());
            if (response.statusCode() >= 400) {
                String err = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("LLM upstream {} → HTTP {}: body={}", llm.baseUrl(), response.statusCode(), err);
                sseLog.warn("upstream ERROR body={}", abbreviateForSse(err, 400));
                return new StreamTurnResult(true, "上游 " + response.statusCode() + ": " + friendlyUpstreamError(err),
                        "", "", null, List.of(), null);
            }
            boolean done = false;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) { done = true; break; }
                    JsonNode chunkRoot;
                    try {
                        chunkRoot = objectMapper.readTree(payload);
                    } catch (Exception parseError) {
                        continue; // keep-alive comments / partial lines
                    }
                    // 流内错误块:部分中转以 HTTP 200 开流,再把上游 400 塞进 data 里
                    // (形如 data: {"error": {"message": "{...invalid_reasoning_effort...}"}})。
                    // 此前该块没有 choices,被静默忽略 → 最终只报 "empty stream",
                    // 真正的错误文案(以及依赖它的降级判定)全部丢失。
                    // 判定收紧:仅 textual 非空 或 object 非空才算错误——{"error":false}/
                    // {"error":0}/{"error":{}} 这类假值块不能打断正常流。
                    JsonNode errNode = chunkRoot.path("error");
                    if ((errNode.isTextual() && !errNode.asText().isBlank())
                            || (errNode.isObject() && !errNode.isEmpty())) {
                        // 取原始 message 文本(可能仍是转义 JSON),交给 friendlyUpstreamError 统一解析
                        String errText = errNode.isTextual() ? errNode.asText()
                                : errNode.path("message").asText("");
                        if (errText.isBlank()) errText = errNode.toString();
                        log.warn("LLM upstream in-stream error: {}", Texts.abbreviate(errText, 300));
                        sseLog.warn("upstream in-stream ERROR body={}", abbreviateForSse(errText, 400));
                        return new StreamTurnResult(true, "上游 " + friendlyUpstreamError(errText),
                                content.toString(), reasoning.toString(), null, List.of(), usage);
                    }
                    // usage rides the last chunk with an empty choices array (relay-verified)
                    JsonNode usageNode = chunkRoot.path("usage");
                    if (usageNode.isObject() && !usageNode.isEmpty()) {
                        usage = new TokenUsage(
                                usageNode.path("prompt_tokens").isInt() ? usageNode.path("prompt_tokens").asInt() : null,
                                usageNode.path("completion_tokens").isInt() ? usageNode.path("completion_tokens").asInt() : null,
                                usageNode.path("total_tokens").isInt() ? usageNode.path("total_tokens").asInt() : null);
                    }
                    JsonNode delta = chunkRoot.path("choices").path(0).path("delta");
                    JsonNode rc = delta.path("reasoning_content");
                    if (!rc.isTextual()) rc = delta.path("reasoning");
                    if (rc.isTextual() && !rc.asText().isEmpty()) {
                        reasoning.append(rc.asText());
                        sink.accept(null, rc.asText());
                    }
                    JsonNode ct = delta.path("content");
                    if (ct.isTextual() && !ct.asText().isEmpty()) {
                        content.append(ct.asText());
                        sink.accept(ct.asText(), null);
                    }
                    JsonNode tcs = delta.path("tool_calls");
                    if (tcs.isArray()) {
                        for (JsonNode tc : tcs) {
                            int idx = tc.path("index").asInt(callIds.size());
                            callIds.computeIfAbsent(idx, k -> tc.path("id").asText(""));
                            JsonNode fn = tc.path("function");
                            if (fn.has("name") && fn.path("name").isTextual() && !fn.path("name").asText().isEmpty()) {
                                // 工具名只完整出现一次;若中转把 name 分片/重复发送(含 JSON null),
                                // 直接覆盖而非拼串,避免拼成 "execute_sqlnullnull…"
                                callNames.put(idx, fn.path("name").asText());
                            }
                            JsonNode args = fn.path("arguments");
                            if (args.isTextual() && !args.asText().isEmpty()) {
                                callArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(args.asText());
                            }
                        }
                    }
                }
            }
            if (!done && content.isEmpty() && reasoning.isEmpty() && callArgs.isEmpty()) {
                return new StreamTurnResult(true, "empty stream", "", "", null, List.of(), usage);
            }
            // rebuild tool_calls in index order with accumulated ids/names/args
            for (Integer idx : new java.util.TreeSet<>(callArgs.isEmpty() ? callIds.keySet() : unionKeys(callIds, callArgs))) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("id", callIds.getOrDefault(idx, "call_" + idx));
                ObjectNode fn = call.putObject("function");
                fn.put("name", callNames.getOrDefault(idx, ""));
                fn.put("arguments", callArgs.containsKey(idx) ? callArgs.get(idx).toString() : "{}");
                orderedCalls.add(call);
            }
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", content.toString());
            if (!orderedCalls.isEmpty()) {
                ArrayNode arr = assistant.putArray("tool_calls");
                for (JsonNode call : orderedCalls) {
                    // 部分上游(实测 DeepSeek-V4-Flash)严格要求每个 tool_call 带
                    // "type":"function",缺失即 400(报错被中转粒化为 "Upstream error: 400",
                    // 极难定位)。这里统一补齐,对宽松上游无副作用。
                    if (call.isObject() && !call.has("type")) {
                        ((ObjectNode) call).put("type", "function");
                    }
                    arr.add(call);
                }
            }
            sseLog.info("upstream round done: contentChars={} reasoningChars={} toolCalls={} usage={}",
                    content.length(), reasoning.length(), orderedCalls.size(),
                    usage == null ? "none" : "in=" + usage.inputTokens() + " out=" + usage.outputTokens());
            return new StreamTurnResult(false, null, content.toString(), reasoning.toString(),
                    assistant, orderedCalls, usage);
        } catch (Exception e) {
            // 用户取消时 HttpClient 阻塞读抛 IOException(InterruptedException):
            // 恢复中断标志让编排层据此短路(不做空响应重试/最终回答兜底)
            if (isInterruption(e)) {
                Thread.currentThread().interrupt();
                sseLog.warn("upstream STREAM FAILED: {} (turn cancelled)", e.toString());
            } else {
                sseLog.warn("upstream STREAM FAILED: {}", e.toString());
            }
            log.error("LLM upstream stream failed (protocol={}): {}", llm.protocol(), e.toString(), e);
            return new StreamTurnResult(true, e.toString(), content.toString(), reasoning.toString(),
                    null, List.of(), usage);
        }
    }

    /**
     * Responses API (POST {base}/responses, SSE) variant of {@link #streamUpstream}.
     * Body conversion (chat.completions shape → responses shape):
     * - messages → input[] with type: message(role/content)
     * - assistant tool_calls → output_item message with type: function_call + call_id
     * - tool results → input item type: function_call_output
     * - tools[] flatten function → {type:"function", name, description, parameters}
     * - reasoning_effort passes through; stream_options dropped
     * Event mapping (SSE `event:` lines):
     * - response.output_text.delta            → content token
     * - response.reasoning_summary_text.delta → reasoning token
     * - response.output_item.added (function_call) / response.function_call_arguments.delta → tool accumulation
     * - response.completed → usage from response.usage
     */
    private StreamTurnResult streamUpstreamResponses(ResolvedLlm llm, ObjectNode chatBody, TokenSink sink) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // function_call accumulation keyed by item_id
        Map<String, String> callIds = new LinkedHashMap<>();
        Map<String, String> callNames = new LinkedHashMap<>();
        Map<String, StringBuilder> callArgs = new LinkedHashMap<>();
        TokenUsage usage = null;

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", chatBody.path("model").asText());
            body.put("stream", true);
            if (chatBody.hasNonNull("reasoning_effort")) {
                body.putObject("reasoning").put("effort", chatBody.get("reasoning_effort").asText());
            }
            // 摘要推理:部分模型(如 muse 系列)原始推理内容是 encrypted_content,不请求
            // summary 就没有任何可见的思考文本——上游支持时必须带 summary=auto,
            // 否则前端"思考过程"时间线对这类模型永远是空的。
            if (!body.has("reasoning")) {
                body.putObject("reasoning").put("summary", "auto");
            } else {
                JsonNode reasoningNode = body.path("reasoning");
                if (reasoningNode.isObject()) {
                    ((ObjectNode) reasoningNode).put("summary", "auto");
                }
            }
            // tools: flatten {type:function, function:{...}} → {type:function, name, ...}
            ArrayNode tools = body.putArray("tools");
            for (JsonNode t : chatBody.path("tools")) {
                if (!"function".equals(t.path("type").asText())) continue;
                ObjectNode flat = tools.addObject();
                flat.put("type", "function");
                flat.put("name", t.path("function").path("name").asText());
                flat.put("description", t.path("function").path("description").asText(""));
                flat.set("parameters", t.path("function").path("parameters"));
            }
            // messages → input[]; tool results → function_call_output items.
            // Responses API has no system role: the system prompt rides as instructions.
            StringBuilder instructions = new StringBuilder();
            ArrayNode input = body.putArray("input");
            for (WireMessage wm : wireMessagesOf(chatBody)) {
                ObjectNode m = wm.node();
                String role = m.path("role").asText("");
                if ("tool".equals(role)) {
                    ObjectNode item = input.addObject();
                    item.put("type", "function_call_output");
                    item.put("call_id", m.path("tool_call_id").asText(""));
                    JsonNode toolContent = m.path("content");
                    if (toolContent.isArray()) {
                        // 多模态工具结果:转为 output 数组(input_text + input_image)。
                        // 实测验证:responses 协议的 function_call_output.output 支持该形式。
                        ArrayNode outParts = item.putArray("output");
                        for (JsonNode part : toolContent) {
                            String pType = part.path("type").asText("");
                            if ("text".equals(pType)) {
                                outParts.addObject().put("type", "input_text").put("text", part.path("text").asText(""));
                            } else if ("image_url".equals(pType)) {
                                outParts.addObject().put("type", "input_image")
                                        .put("image_url", part.path("image_url").path("url").asText(""));
                            }
                        }
                    } else {
                        item.put("output", toolContent.asText(""));
                    }
                    continue;
                }
                if ("system".equals(role)) {
                    if (instructions.length() > 0) instructions.append("\n\n");
                    instructions.append(m.path("content").asText(""));
                    continue;
                }
                ObjectNode msgItem = input.addObject();
                msgItem.put("type", "message");
                msgItem.put("role", "assistant".equals(role) ? "assistant" : "user");
                ArrayNode parts = msgItem.putArray("content");
                ObjectNode part = parts.addObject();
                part.put("type", "assistant".equals(role) ? "output_text" : "input_text");
                part.put("text", m.path("content").asText(""));
                // assistant turn that issued tool_calls: emit function_call items after the message
                JsonNode calls = m.path("tool_calls");
                if (calls.isArray()) {
                    for (JsonNode c : calls) {
                        ObjectNode callItem = input.addObject();
                        callItem.put("type", "function_call");
                        callItem.put("call_id", c.path("id").asText());
                        callItem.put("name", c.path("function").path("name").asText());
                        callItem.put("arguments", c.path("function").path("arguments").asText("{}"));
                    }
                }
            }
            if (instructions.length() > 0) {
                body.put("instructions", instructions.toString());
            }

            java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder
                    .addressFor(llm.baseUrl());
            if (proxyAddr != null) {
                clientBuilder.proxy(java.net.ProxySelector.of(proxyAddr));
            }
            java.net.http.HttpClient client = clientBuilder.build();
            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(stripTrailingSlash(llm.baseUrl()) + "/responses"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llm.apiKey())
                    .header("Accept", MediaType.ALL_VALUE);
            applyExtraHeaders(requestBuilder, llm);
            java.net.http.HttpRequest request = requestBuilder
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            sseLog.info("upstream POST {} -> HTTP {} (model={})",
                    stripTrailingSlash(llm.baseUrl()), response.statusCode(), llm.model());
            if (response.statusCode() >= 400) {
                String err = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("LLM upstream {} → HTTP {}: body={}", llm.baseUrl(), response.statusCode(), err);
                sseLog.warn("upstream ERROR body={}", abbreviateForSse(err, 400));
                return new StreamTurnResult(true, "上游 " + response.statusCode() + ": " + friendlyUpstreamError(err),
                        "", "", null, List.of(), null);
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                String eventName = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) { eventName = line.substring(6).trim(); continue; }
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) break;
                    JsonNode root;
                    try { root = objectMapper.readTree(payload); } catch (Exception e) { continue; }
                    String type = root.path("type").asText(eventName);
                    // 流内错误:responses 协议有标准失败事件(response.failed / error),
                    // 部分中转还会把上游错误塞进 data 的 error 字段。与 chat 路径同款:
                    // 取出真实错误文案返回 failed——否则只报 "empty stream",依赖错误
                    // 文案的降级判定(invalid_reasoning_effort)永远不会触发。
                    if ("error".equals(type) || "response.failed".equals(type)
                            || (root.path("error").isObject() && !root.path("error").isEmpty())
                            || (root.path("error").isTextual() && !root.path("error").asText().isBlank())) {
                        JsonNode errNode = root.path("error");
                        if (errNode.isMissingNode() || errNode.isNull()) {
                            errNode = root.path("response").path("error");
                        }
                        String errText = errNode.isTextual() ? errNode.asText()
                                : errNode.path("message").asText("");
                        if (errText.isBlank()) errText = errNode.isMissingNode() ? root.toString() : errNode.toString();
                        log.warn("LLM upstream in-stream error (responses): {}", Texts.abbreviate(errText, 300));
                        sseLog.warn("upstream in-stream ERROR body={}", abbreviateForSse(errText, 400));
                        return new StreamTurnResult(true, "上游 " + friendlyUpstreamError(errText),
                                content.toString(), reasoning.toString(), null, List.of(), usage);
                    }
                    if ("response.output_text.delta".equals(type)) {
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) { content.append(delta); sink.accept(delta, null); }
                    } else if ("response.reasoning_summary_text.delta".equals(type)
                            || "response.reasoning_text.delta".equals(type)) {
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) { reasoning.append(delta); sink.accept(null, delta); }
                    } else if ("response.output_item.added".equals(type)) {
                        JsonNode item = root.path("item");
                        if ("function_call".equals(item.path("type").asText())) {
                            String itemId = item.path("id").asText();
                            callIds.putIfAbsent(itemId, item.path("call_id").asText(itemId));
                            callNames.putIfAbsent(itemId, item.path("name").asText(""));
                            callArgs.computeIfAbsent(itemId, k -> new StringBuilder())
                                    .append(item.path("arguments").asText(""));
                        }
                    } else if ("response.function_call_arguments.delta".equals(type)) {
                        String itemId = root.path("item_id").asText("");
                        String delta = root.path("delta").asText("");
                        if (!itemId.isEmpty() && !delta.isEmpty()) {
                            callArgs.computeIfAbsent(itemId, k -> new StringBuilder()).append(delta);
                        }
                    } else if ("response.completed".equals(type)) {
                        JsonNode u = root.path("response").path("usage");
                        if (u.isObject() && !u.isEmpty()) {
                            usage = new TokenUsage(
                                    u.path("input_tokens").isInt() ? u.path("input_tokens").asInt() : null,
                                    u.path("output_tokens").isInt() ? u.path("output_tokens").asInt() : null,
                                    u.path("total_tokens").isInt() ? u.path("total_tokens").asInt() : null);
                        }
                    }
                }
            }
            if (content.isEmpty() && reasoning.isEmpty() && callArgs.isEmpty()) {
                return new StreamTurnResult(true, "empty stream", "", "", null, List.of(), usage);
            }
            // rebuild assistant message: content + tool_calls (chat.completions shape, so the
            // ReAct loop / persistence layers stay protocol-agnostic)
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", content.toString());
            List<JsonNode> orderedCalls = new ArrayList<>();
            for (String itemId : callArgs.keySet()) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("id", callIds.getOrDefault(itemId, itemId));
                ObjectNode fn = call.putObject("function");
                fn.put("name", callNames.getOrDefault(itemId, ""));
                fn.put("arguments", callArgs.get(itemId).toString());
                orderedCalls.add(call);
            }
            if (!orderedCalls.isEmpty()) {
                ArrayNode arr = assistant.putArray("tool_calls");
                for (JsonNode call : orderedCalls) {
                    // 同 openai 路径:补齐 "type":"function"(部分上游严格校验)
                    if (call.isObject() && !call.has("type")) {
                        ((ObjectNode) call).put("type", "function");
                    }
                    arr.add(call);
                }
            }
            // reasoningChars 必须记录:此前 responses 分支漏了这一项,排查
            // 「思考等级失效」时只能靠直连上游重放才能判断上游到底有没有产推理,
            // 日志里看不出(openai 分支一直是全的)。
            sseLog.info("upstream round done (responses): contentChars={} reasoningChars={} toolCalls={} usage={}",
                    content.length(), reasoning.length(), orderedCalls.size(),
                    usage == null ? "none" : "in=" + usage.inputTokens() + " out=" + usage.outputTokens());
            return new StreamTurnResult(false, null, content.toString(), reasoning.toString(),
                    assistant, orderedCalls, usage);
        } catch (Exception e) {
            if (isInterruption(e)) {
                Thread.currentThread().interrupt();
                sseLog.warn("upstream STREAM FAILED: {} (turn cancelled)", e.toString());
            } else {
                sseLog.warn("upstream STREAM FAILED: {}", e.toString());
            }
            log.error("LLM upstream stream failed (protocol={}): {}", llm.protocol(), e.toString(), e);
            return new StreamTurnResult(true, e.toString(), content.toString(), reasoning.toString(),
                    null, List.of(), usage);
        }
    }

    /** chat.completions body's messages array as WireMessage list (responses conversion helper). */
    private List<WireMessage> wireMessagesOf(ObjectNode chatBody) {
        List<WireMessage> result = new ArrayList<>();
        for (JsonNode m : chatBody.path("messages")) {
            if (m instanceof ObjectNode o) result.add(new WireMessage(o));
        }
        return result;
    }

    private static java.util.Set<Integer> unionKeys(Map<Integer, String> a, Map<Integer, StringBuilder> b) {
        java.util.Set<Integer> all = new java.util.TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        return all;
    }

    /**
     * Per-provider extra HTTP headers. OpenCode's free tier rejects requests
     * without an X-Session-ID ("free tier can only be used in OpenCode");
     * the ID is derived from the API key so it stays stable across turns
     * (provider-side session continuity) without leaking anything.
     */
    /** DEBUG 报文日志:协议/模型/档位/URL/请求体(api_key 不入日志,Authorization 头不拼进 body)。 */
    private void logUpstreamRequest(ResolvedLlm llm, ObjectNode body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            log.debug("LLM upstream request: protocol={} model={} level={} url={}/... body={}",
                    llm.protocol(), llm.model(), llm.effectiveReasoningLevel(),
                    stripTrailingSlash(llm.baseUrl()), objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.debug("LLM upstream request: protocol={} model={} url={} (body serialize failed: {})",
                    llm.protocol(), llm.model(), llm.baseUrl(), e.toString());
        }
    }

    /**
     * Provider 专属附加头(opencode 需要 X-Session-ID)。
     * 空数组时必须跳过 {@code HttpRequest.Builder.headers(String...)}:
     * JDK 对 0 个参数同样抛 IAE "wrong number, 0, of parameters"(varargs
     * 成对校验不接受空数组),曾在所有普通 provider 上导致每轮请求必失败。
     */
    private java.net.http.HttpRequest.Builder applyExtraHeaders(java.net.http.HttpRequest.Builder builder, ResolvedLlm llm) {
        String[] extra = providerExtraHeaders(llm);
        if (extra.length > 0) {
            builder.headers(extra);
        }
        return builder;
    }

    private String[] providerExtraHeaders(ResolvedLlm llm) {
        if (llm.baseUrl() != null && llm.baseUrl().contains("opencode.ai")) {
            String key = llm.apiKey() == null ? "" : llm.apiKey();
            String sessionId = "nora-" + Integer.toHexString(key.hashCode());
            return new String[]{"X-Session-ID", sessionId};
        }
        return new String[0];
    }

    private static String stripTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    private ObjectNode baseBody(boolean stream, ResolvedLlm llm) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llm.model());
        body.put("stream", stream);
        applyReasoningRequest(body, llm);
        return body;
    }

    /**
     * OpenAI-compatible reasoning switches; models with native reasoning need no override.
     * 生效等级(effectiveReasoningLevel)来自设置页 per-model 配置或对话框请求:
     * - 用户显式选的档位一律原样透传(含 none=关闭);家族名单只决定“未指定时的默认值”:
     *   gpt-5/o 默认 medium,claude thinking 与带档位后缀的模型不注入
     *   (由上游按模型自身默认决定,claude 不带该字段就没有推理内容)。
     *   上游拒绝该取值时自动剥离并重试,重试成功才记住该档位(见 effortRejectedModels)。
     * - qwen/glm:开关式字段,none 关、其余开。
     */
    private void applyReasoningRequest(ObjectNode body, ResolvedLlm llm) {
        // 协议白名单只排除 ollama:其原生 /api/chat 不认 reasoning_effort。
        // openai / responses / anthropic 都注入——
        //   responses 走 reasoning:{effort} 映射(见 streamUpstreamResponses);
        //   anthropic 在本实现里同样以 chat.completions 形状发出(见 streamUpstream 协议分发),
        //   档位也用 reasoning_effort。
        // 此前按协议名直接 return,anthropic 选了档位也发不出去(实测 2026-09-15:
        // protocol=anthropic 的请求体里没有 reasoning_effort,上游 reasoningChars=0;
        // 同一 key 手工加 reasoning_effort=xhigh 立刻出 261 字符推理)。
        // 不认该字段的上游由既有的降级重试兜底(见 effortRejectedModels)。
        if ("ollama".equalsIgnoreCase(llm.protocol())) return;
        String model = llm.model() == null ? "" : llm.model().toLowerCase();
        String requested = llm.effectiveReasoningLevel();
        boolean hasRequested = requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested);
        boolean off = hasRequested && "none".equalsIgnoreCase(requested);
        boolean effortFamily = supportsReasoningEffort(model);
        boolean openAiDefaultFamily = model.contains("gpt-5") || model.matches("(?s).*\\bo[1-9].*");
        // 已探测到该模型拒绝**这个档位**:跳过注入,避免每次都先撞 400 再重试。
        // 键含档位——用户换成受支持的等级后仍会正常注入(见 effortKey)。
        // 只跳过 reasoning_effort,qwen/glm 的开关式字段仍照常处理(它们在下面)。
        boolean effortRejected = effortRejectedModels.contains(effortKey(llm));
        // 用户显式选的档位一律透传——家族白名单只管"未指定时的默认值"。
        // 此前把显式档位也锁在 effortFamily 里,导致不在名单的模型(如 deepseek-v4.1-flash)
        // 选了档位却被静默丢弃:responses 分支拿不到 reasoning_effort 就只发 summary:auto,
        // 上游实测该形态不产出任何 reasoning(0 事件),思考等级等于失效。
        // 实测(2026-09-15, 中转站 192.168.0.109:28765):
        //   chat  + reasoning_effort=xhigh  → 4397 分片
        //   chat  + reasoning_effort=minimal→ 5878 分片
        //   responses + reasoning{effort:xhigh,summary:auto} → 7414 分片
        //   responses + reasoning{summary:auto}(无 effort)    → 0 分片
        // 不认该字段的模型家族由上游忽略即可,不会报错(glm 同发 reasoning_effort + thinking 实测 200)。
        if (!effortRejected && (effortFamily || hasRequested)) {
            if (off) {
                // 「none = 关闭思考」直传 none,不再降级成 minimal。
                // 旧实现固定写 minimal,但实测该上游的 minimal 仍会思考
                // (deepseek-v4.1-flash: responses+minimal → 2788 reasoning 分片),
                // 导致用户选「none」根本关不掉;none 本身被上游接受(200)。
                body.put("reasoning_effort", "none");
            } else if (hasRequested && isOpenAiEffort(requested)) {
                body.put("reasoning_effort", requested);
            } else if (openAiDefaultFamily) {
                // 历史默认:OpenAI 家族不指定时也要 medium(上游不默认开推理)
                body.put("reasoning_effort", "medium");
            }
            // 其余家族(claude/带后缀)未指定时不注入,交由上游默认
        }
        if (model.contains("qwen")) {
            body.put("enable_thinking", !off);
        }
        if (model.contains("glm")) {
            body.putObject("thinking").put("type", off ? "disabled" : "enabled");
        }
    }

    /** OpenAI reasoning_effort 合法值(截至今日上游公开档位)。 */
    private static boolean isOpenAiEffort(String level) {
        return switch (level.toLowerCase()) {
            case "none", "minimal", "low", "medium", "high", "xhigh", "max" -> true;
            default -> false;
        };
    }

    /**
     * 是否支持/需要 reasoning_effort 透传的模型家族:
     * OpenAI(gpt-5/o)、Anthropic thinking(claude-*-thinking,实测 2026-09-05:
     * 不带 reasoning_effort 时中转站不返回 reasoning_content)、
     * 中转站档位后缀命名(gemini-*-high 等)。
     */
    private static boolean supportsReasoningEffort(String model) {
        if (model.contains("gpt-5") || model.matches("(?s).*\\bo[1-9].*")) return true;
        if (model.contains("claude")) return true;
        // 中转站为非 OpenAI 模型附加的思考档位后缀:gemini-3.6-flash-high / deepseek-v4-pro-high 等
        return model.matches("(?s).*(high|xhigh|max|minimal|low)$");
    }

    /** Reads the common reasoning fields emitted by OpenAI-compatible relays. */
    private String messageReasoning(JsonNode message) {
        JsonNode node = message.path("reasoning_content");
        if (!node.isTextual()) node = message.path("reasoning");
        if (!node.isTextual()) node = message.path("thinking");
        return node.isTextual() ? node.asText("") : "";
    }

    /** Returns a copy of the resolved endpoint carrying the given reasoning level. */
    private static ResolvedLlm withLevel(ResolvedLlm llm, String reasoningLevel) {
        if (llm == null) return null;
        return new ResolvedLlm(llm.baseUrl(), llm.apiKey(), llm.model(), llm.protocol(), reasoningLevel,
                llm.contextWindow(), llm.vision());
    }

    /** Returns a copy of the resolved endpoint carrying the given reasoning level. */

    /** Package-visible for tests; never returned outside the service. */
    record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,
                       /** 生效思考等级(已合并请求级与设置页默认);null = auto */
                       String effectiveReasoningLevel,
                       /** 模型上下文窗口(tokens);null = 未配置 */
                       Long contextWindow,
                       /**
                        * 识图能力(设置页每模型开关):TRUE/FALSE = 强制;null = 运行时自适应
                        * (默认尝试附加图片,上游以“不支持图片”拒绝时自动剥离并记忆)。
                        */
                       Boolean vision) {}

    private ArrayNode messagesArray(List<WireMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (WireMessage message : messages) {
            array.add(message.node());
        }
        return array;
    }

    /** tools spec 装配委托(实现见 ChatToolsSpec,2026-09-17 拆分)。 */
    private ArrayNode toolsSpec() {
        return toolsSpecBuilder.build();
    }

    /**
     * 系统性上下文装配(设计见 docs/context-management-design.md):
     * 固定层(system prompt + RAG 片段 + 反思 + 新消息)之外,历史按 token
     * 预算从新到旧装入;预算 = 触发线 - 输出预留 - 固定开销。估算统一走
     * {@link ContextBudget#estimateTokens}(CJK 感知);上游真实 usage 只做
     * 展示与事后校准,不参与装配决策。至少保留 1 条历史,条数硬上限 40。
     */
    private List<WireMessage> buildMessages(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<CitationDto> citations,
                                            List<String> reflections,
                                            ContextBudget budget,
                                            SystemPromptResult[] promptOut) {
        List<WireMessage> messages = new ArrayList<>();
        SystemPromptResult prompt = systemPromptWith(citations);
        promptOut[0] = prompt;
        String systemPrompt = prompt.text();
        messages.add(WireMessage.system(objectMapper, systemPrompt));
        String reflectionBlock = reflections == null || reflections.isEmpty() ? null
                : "此前类似任务的失败反思（仅作参考）：\n- " + String.join("\n- ", reflections);
        if (reflectionBlock != null) {
            messages.add(WireMessage.system(objectMapper, reflectionBlock));
        }

        int fixedCost = ContextBudget.estimateTokens(systemPrompt)
                + (reflectionBlock == null ? 0 : ContextBudget.estimateTokens(reflectionBlock))
                + ContextBudget.estimateTokens(userMessage)
                + (int) requestOverheadTokens();
        long historyBudget = budget.historyBudgetTokens(fixedCost);
        int used = 0;
        // controller 在调用前已把当前 user 消息落库(loadMessages 的末条就是它),
        // 这里只拼历史部分并排除末条,末尾统一 add(userMessage)——否则当前消息
        // 会被发两遍(中转/上游按两条独立 user 消息计费并处理)
        int historyEnd = history.size();
        if (historyEnd > 0) {
            ChatStoreService.StoredMessage last = history.get(historyEnd - 1);
            if ("user".equals(last.role()) && userMessage.equals(last.content())) {
                historyEnd--;
            }
        }
        int from = historyEnd;
        while (from > 0) {
            ChatStoreService.StoredMessage prev = history.get(from - 1);
            int cost = wireCostOf(prev);
            if (used + cost > historyBudget && from < historyEnd) break; // 至少保留 1 条
            used += cost;
            from--;
            if (historyEnd - from >= 40) break; // 条数硬上限,防御超长单条
        }
        for (int i = from; i < historyEnd; i++) {
            appendHistoryMessage(messages, history.get(i));
        }
        messages.add(WireMessage.user(objectMapper, userMessage));
        return messages;
    }

    /**
     * 把一条持久化历史消息按 wire 结构重建进请求(对齐 Claude Code/Codex 的
     * 「工具链即历史」):assistant 消息若带可重建的工具步骤(有 toolName +
     * 脱敏原始参数 + 终态结果),按 roundIndex 逐轮重放为
     * assistant(tool_calls)+ tool(result) 对,最后附回答文本——模型看到
     * 自己真实的调用记录与「调用→结果」节奏,而不是被剥掉结构的纯文本
     * 总结(实测:缺失该结构时弱模型会续写编造工具结果)。
     * 旧数据(无 rawArgs)优雅退化为纯文本,不产生孤儿 tool 消息。
     */
    private void appendHistoryMessage(List<WireMessage> messages, ChatStoreService.StoredMessage msg) {
        if ("user".equals(msg.role())) {
            messages.add(WireMessage.user(objectMapper, msg.content()));
            return;
        }
        if (!"assistant".equals(msg.role())) {
            return;
        }
        String content = msg.content() == null ? "" : msg.content();
        List<ChatStepDto> toolSteps = rebuildableToolSteps(msg.steps());
        if (toolSteps.isEmpty()) {
            if (!content.isBlank()) {
                messages.add(WireMessage.assistant(objectMapper, content));
            }
            return;
        }
        // 按轮次分组:同一轮的多工具调用是一批(assistant 一条 + 多个 tool 结果)
        java.util.LinkedHashMap<Integer, List<ChatStepDto>> byRound = new java.util.LinkedHashMap<>();
        for (ChatStepDto step : toolSteps) {
            byRound.computeIfAbsent(step.roundIndex(), k -> new ArrayList<>()).add(step);
        }
        for (List<ChatStepDto> roundSteps : byRound.values()) {
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", "");
            ArrayNode calls = assistant.putArray("tool_calls");
            for (ChatStepDto step : roundSteps) {
                ObjectNode call = calls.addObject();
                call.put("id", step.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", step.toolName());
                fn.put("arguments", step.input().rawArgs());
            }
            messages.add(new WireMessage(assistant));
            for (ChatStepDto step : roundSteps) {
                ObjectNode toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", step.id());
                toolMsg.put("content", toolResultText(step));
                messages.add(new WireMessage(toolMsg));
            }
        }
        if (!content.isBlank()) {
            messages.add(WireMessage.assistant(objectMapper, content));
        }
    }

    /** 可重建为 wire tool_call 的步骤:工具名 + 脱敏原始参数齐备(结果缺省也有占位)。 */
    private static List<ChatStepDto> rebuildableToolSteps(List<ChatStepDto> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(s -> "tool".equals(s.type())
                        && s.toolName() != null && !s.toolName().isBlank()
                        && s.input() != null && s.input().rawArgs() != null && !s.input().rawArgs().isBlank())
                .toList();
    }

    /** 重放给模型的工具结果文本:成功用 content;declined/失败无 content 时用 error(与轮内回填同语义)。 */
    private static String toolResultText(ChatStepDto step) {
        ChatStepDto.StepResult result = step.result();
        if (result == null) {
            return "(no output)";
        }
        if (result.content() != null) {
            return result.content();
        }
        if (result.error() != null) {
            return "ERROR: " + result.error();
        }
        return "(no output)";
    }

    /** 一条历史消息重建后的 wire token 估算(含工具链;预算循环用,与 messageTokens 同口径)。 */
    private static int wireCostOf(ChatStoreService.StoredMessage msg) {
        if ("user".equals(msg.role())) {
            return ContextBudget.estimateTokens(msg.content()) + 8;
        }
        if (!"assistant".equals(msg.role())) {
            return 8;
        }
        int cost = ContextBudget.estimateTokens(msg.content() == null ? "" : msg.content()) + 8;
        for (ChatStepDto step : rebuildableToolSteps(msg.steps())) {
            cost += ContextBudget.estimateTokens(step.toolName())
                    + ContextBudget.estimateTokens(step.input().rawArgs())
                    + ContextBudget.estimateTokens(toolResultText(step)) + 24;
        }
        return cost;
    }

    /** 轮内微压缩永不触碰的尾部消息数(最近一轮工具往返 + 余量)。 */
    private static final int COMPACTION_TAIL_KEEP = 6;

    /**
     * 保留图片附件的最近工具批次数(含当前批次;更早批次的图片在下一轮请求前回收)。
     *
     * <p>为什么按"轮"而不是"条":实测(2026-09-13)按条保留 2 条时,模型在
     * 第 3 轮请求 4 张图后,第 4 轮(最终回答)前这 4 张里较早的 2 张已被回收——
     * 模型尚未把结论写进文本,只能如实报告"看不到画面"。按轮保留 2 个批次
     * 覆盖"当前批次 + 上一批次",让模型有完整的窗口消化刚看过的图。
     * 模型看过的更早的图通常已消化成结论;需要回看时可按编号清单里的 id
     * 重新取图,比让每轮请求体无限膨胀(每张 data URL ~600KB)更划算。
     */
    private static final int COMPACTION_KEEP_RECENT_IMAGE_ROUNDS = 2;

    /**
     * 每次请求的固定 overhead token 估算下限(tools spec + 协议封装/系统字段)。
     *
     * <p>实测(2026-09-13):启用 MCP 后 tools spec 已膨胀到 75 个工具/74KB
     * (≈2 万 tokens)——固定 1800 严重低估,导致 promptEstimate 4051 vs
     * 真实 11565(ratio 2.85),压缩触发偏晚。改为按 toolsSpec() 实际序列化
     * 长度动态估算(见 {@link #toolsOverheadTokens()}),此常量降级为下限
     * (无 tools 的路径/序列化失败时兜底)。
     */
    static final int PER_REQUEST_OVERHEAD_TOKENS = 1_800;

    /** tools spec 序列化缓存的长度(每进程一次:工具集在运行期不变)。 */
    private volatile int toolsSpecTokensCache = -1;

    /**
     * tools spec 的 token 估算(按实际序列化长度)。
     *
     * <p>口径与消息体不同:实测(2026-09-13,空会话单请求)body 77174 字符
     * → 上游上报 in=11395;扣掉消息体估算(≈2.4K)后 tools 段 73792 字符
     * ≈ 9K tokens,即 **≈7-8 字符/token**——JSON 键名/语法高度重复,
     * tokenizer 打包效率远高于普通文本。若沿用消息体的 CJK 感知估算
     * (≈20K)会过估 2 倍,导致压缩过早触发(实测 ratio 0.47)。
     * 取 /7 略偏保守(宁可略早压缩)。工具集运行期不变,首次计算后缓存。
     */
    int toolsOverheadTokens() {
        int cached = toolsSpecTokensCache;
        if (cached >= 0) return cached;
        int tokens;
        try {
            String json = objectMapper.writeValueAsString(toolsSpec());
            tokens = Math.max(PER_REQUEST_OVERHEAD_TOKENS, json.length() / TOOLS_SPEC_CHARS_PER_TOKEN);
        } catch (Exception e) {
            log.warn("tools spec serialize failed, fallback to constant overhead: {}", e.toString());
            tokens = PER_REQUEST_OVERHEAD_TOKENS;
        }
        toolsSpecTokensCache = tokens;
        return tokens;
    }

    /** tools spec 的字符/token 经验值(实测 7-8;取 7 略保守)。 */
    private static final int TOOLS_SPEC_CHARS_PER_TOKEN = 7;

    /** 本轮请求的真实固定开销 = tools spec(动态) + 协议封装余量。 */
    private long requestOverheadTokens() {
        return toolsOverheadTokens();
    }

    /*
     * 注:不再单列"协议封装"常量。实测(空会话单请求)76K body → in=11550,
     * 其中消息体仅 ~2K 字符——即 74K 的 tools 段实际承担了 ~11K tokens,
     * 已含协议封装/系统字段。再叠加一个固定常量会重复计算(实测 ratio 0.61)。
     */

    /**
     * 轮内微压缩(microcompact,语义对齐 Claude Code):预估 prompt 超过
     * 触发线时,把最旧的工具结果就地改写为头尾摘录。只改 content,role/
     * tool_call_id 不动(OpenAI tool_call 配对校验不破),最近
     * {@link #COMPACTION_TAIL_KEEP} 条消息完整保留;历史轮的失败也只留要点。
     * force=true(上游已报超限)时压缩目标降为恢复线(窗口 60%)。
     */
    private int compactForRound(List<WireMessage> messages, ContextBudget budget, boolean force,
                                long overheadTokens) {
        // 旧图回收:与 token 预算无关的带宽护栏——每轮都执行(见 recycleOldImages)
        int recycledImages = recycleOldImages(messages, force);
        lastRecycledImages = recycledImages;
        long total = 0;
        for (WireMessage m : messages) {
            total += messageTokens(m);
        }
        total += overheadTokens; // tools spec + 协议封装,占请求大头
        long limit = force
                ? (long) (budget.window() * ContextBudget.RECOVERY_RATIO)
                : budget.triggerTokens() - budget.outputReserve();
        if (total <= limit) {
            estimateTokensFreed = 0;
            return 0;
        }
        int compactedCount = 0;
        long freed = 0;
        int lastCompactable = messages.size() - COMPACTION_TAIL_KEEP;
        for (int i = 1; i < lastCompactable && total > limit; i++) { // i=0 system 不动
            WireMessage m = messages.get(i);
            if (!"tool".equals(m.node().path("role").asText())) continue;
            JsonNode contentNode = m.node().path("content");
            if (contentNode.isArray()) {
                // 多模态工具结果(带图):只压缩文本部分(图片回收在 recycleOldImages)
                for (JsonNode part : contentNode) {
                    if (!"text".equals(part.path("type").asText())) continue;
                    String t = part.path("text").asText("");
                    String compacted = compactToolContent(t);
                    if (compacted.equals(t)) continue;
                    freed += ContextBudget.estimateTokens(t) - ContextBudget.estimateTokens(compacted);
                    total -= ContextBudget.estimateTokens(t) - ContextBudget.estimateTokens(compacted);
                    compactedCount++;
                    ((ObjectNode) part).put("text", compacted);
                }
                continue;
            }
            String content = contentNode.asText("");
            String compacted = compactToolContent(content);
            if (compacted.equals(content)) continue; // 太短不值得
            freed += ContextBudget.estimateTokens(content) - ContextBudget.estimateTokens(compacted);
            total -= ContextBudget.estimateTokens(content) - ContextBudget.estimateTokens(compacted);
            compactedCount++;
            ((ObjectNode) m.node()).put("content", compacted);
        }
        estimateTokensFreed = freed;
        return compactedCount;
    }

    /**
     * 旧图回收:只保留最近 {@link #COMPACTION_KEEP_RECENT_IMAGE_ROUNDS} 个
     * 工具批次的图片附件,更早批次剥离(保留文本与编号清单);尾部
     * {@link #COMPACTION_TAIL_KEEP} 条消息永不触碰。
     *
     * <p>动机:图片 token 计费极低(实测本环境中转几乎不计——每轮追加 4-5 张
     * 全尺寸照片仅使上报输入 +~640 tokens),但每张 data URL 有 ~600KB——
     * 48 张图的找猫任务若全部保留,单请求体将达 ~100MB,上传耗时主导每轮时长。
     * 回收把在途图片稳定压在小窗口内,模型需要回看时可按编号清单重新取图。
     *
     * @return 被剥离图片附件的消息条数
     */
    private int recycleOldImages(List<WireMessage> messages, boolean force) {
        int keepBatches = force ? 1 : COMPACTION_KEEP_RECENT_IMAGE_ROUNDS;
        int lastCompactable = messages.size() - COMPACTION_TAIL_KEEP;
        int batchIndex = 0; // 0 = 当前批次(倒序走到的第一个批次)
        int stripped = 0;
        for (int i = messages.size() - 1; i >= 1; i--) {
            JsonNode node = messages.get(i).node();
            JsonNode calls = node.path("tool_calls");
            if ("assistant".equals(node.path("role").asText()) && calls.isArray() && !calls.isEmpty()) {
                batchIndex++; // 越过该批次的 tool_calls 锚点,再往前即更早批次
                continue;
            }
            JsonNode contentNode = node.path("content");
            if (!contentNode.isArray()) continue;
            boolean hasImg = false;
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) {
                    hasImg = true;
                    break;
                }
            }
            if (!hasImg) continue;
            if (batchIndex < keepBatches || i >= lastCompactable) continue;
            ArrayNode kept = objectMapper.createArrayNode();
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) continue;
                kept.add(part);
            }
            kept.add(objectMapper.createObjectNode()
                    .put("type", "text")
                    .put("text", "(早期图片已省略——如需回看,重新调用对应工具或按编号清单中的 id 单独取图)"));
            ((ObjectNode) messages.get(i).node()).set("content", kept);
            stripped++;
        }
        return stripped;
    }

    /** 最近一次 compactForRound 回收的旧图张数(可视化 step 用)。 */
    private int lastRecycledImages;

    /** 最近一次 compactForRound 释放的 token 估算(可视化 step 用)。 */
    private long estimateTokensFreed;

    /** 单条工具结果的压缩形态:头 800 + 尾 200,失败只留头 400(错误要点在前)。 */
    static String compactToolContent(String content) {
        boolean failure = content.startsWith("ERROR:");
        int head = failure ? 400 : 800;
        int tail = failure ? 0 : 200;
        if (content.length() <= head + tail + 80) return content;
        String body = content.substring(0, head)
                + "\n…[早期工具结果已压缩,原文 " + content.length() + " 字符]…";
        if (tail > 0) {
            body += "\n" + content.substring(content.length() - tail);
        }
        return body;
    }

    /** 一条 wire 消息的 token 估算(content + tool_calls 参数 + 封装)。 */
    private static int messageTokens(WireMessage m) {
        ObjectNode n = m.node();
        JsonNode contentNode = n.path("content");
        int tokens = ContextBudget.estimateTokens(textOfContent(contentNode) == null ? "" : textOfContent(contentNode))
                + 8;
        if (contentNode.isArray()) {
            // 图片按单张固定成本估算(见 IMAGE_TOKEN_ESTIMATE):与 data URL 长度
            // 无关——实测追加 ~14MB 图片仅使上游上报输入 +639 tokens;此前
            // "按 ~16 字符/token"的推断来自把累计 usage 与单轮估算相比的
            // 日志口径错误(见 logCalibration),并非真实计费方式。
            for (JsonNode part : contentNode) {
                if ("image_url".equals(part.path("type").asText())) {
                    tokens += IMAGE_TOKEN_ESTIMATE;
                }
            }
        }
        JsonNode calls = n.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode c : calls) {
                tokens += ContextBudget.estimateTokens(c.path("function").path("name").asText(""))
                        + ContextBudget.estimateTokens(c.path("function").path("arguments").asText("")) + 8;
            }
        }
        return tokens;
    }

    /**
     * 估算校准日志:把**当前轮**的服务端 prompt 估算与该轮上游真实 inputTokens
     * 对齐输出(真实值含 tools spec/协议封装 overhead,估算只含消息体)。
     * 比值持续偏离预期时调整估算系数——观测驱动的闭环。
     *
     * <p>必须传**当轮** usage:曾误传累计 usage 与单轮估算相除,产生
     * ratio=10.73 的虚高告警,并误导出"图片按 data URL 长度计费"的错误结论
     * (实测:追加 14MB 图片仅使上游上报输入 +639 tokens)。
     */
    private void logCalibration(int promptEstimate, TokenUsage usage, ContextBudget budget) {
        if (usage == null || usage.inputTokens() == null || promptEstimate <= 0) return;
        int real = usage.inputTokens();
        double ratio = (double) real / promptEstimate;
        // 只在明显偏离时告警(±35% 外),正常波动打 debug
        String r = String.format("%.2f", ratio);
        if (ratio < 0.65 || ratio > 1.35) {
            log.warn("context estimate calibration: promptEstimate={} realInput={} ratio={} window={} trigger={}",
                    promptEstimate, real, r, budget.window(), budget.triggerTokens());
        } else {
            log.debug("context estimate ok: promptEstimate={} realInput={} ratio={}",
                    promptEstimate, real, r);
        }
    }

    /** 上游上下文超限错误识别(各家中转措辞不一,宽松匹配)。 */
    static boolean isContextOverflow(String errorMessage) {
        if (errorMessage == null) return false;
        String s = errorMessage.toLowerCase();
        return s.contains("context length") || s.contains("maximum context")
                || s.contains("context_length") || s.contains("too many tokens")
                || s.contains("token limit") || s.contains("上下文长度");
    }

    /**
     * 系统提示装配:基础 prompt + 工作区引导(AGENTS/SOUL/USER/MEMORY)+ 技能目录 + RAG 片段。
     * 工作区/目录注入是 best-effort:不可用时静默跳过,不阻断对话。
     *
     * <p>返回文本与注入元数据(工作区文件清单/日记清单/技能条目):调用方据此下发
     * 「注入上下文」步骤,前端可展开查看真实注入内容(dsh 的注入可见性模式)。
     */
    private SystemPromptResult systemPromptWith(List<CitationDto> citations) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        AgentWorkspaceService.BootstrapResult workspaceBootstrap = null;
        AgentSkillService.CatalogBundle skillCatalog = null;
        // 全局记忆:跨会话事实/偏好,约束每轮回答
        if (agentWorkspaceService != null) {
            try {
                workspaceBootstrap = agentWorkspaceService.bootstrap();
                if (workspaceBootstrap != null && !workspaceBootstrap.text().isBlank()) {
                    sb.append('\n').append(workspaceBootstrap.text());
                }
            } catch (Exception e) {
                log.warn("workspace inject failed (ignored): {}", e.getMessage());
            }
        }
        // 技能目录:列出启用技能的名称/描述,正文由 manage_skill action=read 按需拉取
        if (agentSkillService != null) {
            try {
                skillCatalog = agentSkillService.catalogBundle();
                if (skillCatalog != null && !skillCatalog.text().isBlank()) {
                    sb.append('\n').append(skillCatalog.text());
                }
            } catch (Exception e) {
                log.warn("skill catalog inject failed (ignored): {}", e.getMessage());
            }
        }
        if (!citations.isEmpty()) {
            sb.append("\n以下是知识库检索到的相关片段：\n");
            for (CitationDto c : citations) {
                sb.append("[[").append(c.docName()).append("#chunk").append(c.chunkIndex())
                        .append("]] ").append(c.snippet()).append('\n');
            }
        }
        return new SystemPromptResult(sb.toString(), workspaceBootstrap, skillCatalog);
    }

    /** 系统提示文本 + 注入元数据(下发「注入上下文」步骤用;null = 该来源未注入)。 */
    private record SystemPromptResult(String text,
                                      AgentWorkspaceService.BootstrapResult workspace,
                                      AgentSkillService.CatalogBundle skills) {
    }

    /**
     * 下发「注入上下文」步骤(dsh 模式:注入内容对用户可见,自描述 form 声明信息形态)。
     * 工作区引导 → form=instructions;技能目录 → form=catalog。无注入时静默跳过。
     *
     * <p>降噪(2026-09-12):注入本身每轮都发生(系统提示必须随每次请求携带),
     * 但步骤只在<b>首轮或内容较上次下发有变化</b>时下发——与历史里最近一次同名步骤
     * 逐字段对比,一致则跳过(不变的重复展示只是噪音)。内容变化(如 agent 自演化
     * 编辑了记忆文件)仍会重新下发,保证「模型这轮读到的记忆变过」对用户可见。
     */
    private void emitContextSteps(SystemPromptResult prompt, List<ChatStoreService.StoredMessage> history,
                                  ChatEventConsumer eventConsumer) {
        ChatStepDto.ContextInfo prevMemory = lastEmittedContext(history, "s-context-memory");
        ChatStepDto.ContextInfo prevSkills = lastEmittedContext(history, "s-context-skills");
        if (prompt.workspace() != null && !prompt.workspace().files().isEmpty()) {
            List<ChatStepDto.ContextFile> files = prompt.workspace().files().stream()
                    .map(f -> new ChatStepDto.ContextFile(f.path(), f.bytes(), f.truncated(), f.missing(), f.content()))
                    .toList();
            boolean unchanged = prevMemory != null
                    && java.util.Objects.equals(files, prevMemory.files())
                    && java.util.Objects.equals(prompt.workspace().dailyNotes(), prevMemory.dailyNotes());
            if (!unchanged) {
                int injected = prompt.workspace().files().stream()
                        .filter(f -> !f.missing() && !f.truncated())
                        .toList().size();
                String detail = "注入 " + injected + " 个文件"
                        + (prompt.workspace().dailyNotes().isEmpty()
                                ? ""
                                : " + " + prompt.workspace().dailyNotes().size() + " 篇日记清单");
                eventConsumer.step(new ChatStepDto("s-context-memory", "context", "加载长期记忆", detail,
                        0L, "completed", null, null, null, 0,
                        new ChatStepDto.ContextInfo("instructions", "workspace-bootstrap",
                                files, null, prompt.workspace().dailyNotes())));
            }
        }
        if (prompt.skills() != null && !prompt.skills().entries().isEmpty()) {
            List<ChatStepDto.ContextEntry> entries = prompt.skills().entries().stream()
                    .map(e -> new ChatStepDto.ContextEntry(e.name(), e.description(), e.category()))
                    .toList();
            boolean unchanged = prevSkills != null && java.util.Objects.equals(entries, prevSkills.entries());
            if (!unchanged) {
                eventConsumer.step(new ChatStepDto("s-context-skills", "context", "加载技能目录",
                        "启用 " + entries.size() + " 个技能（正文按需读取）",
                        0L, "completed", null, null, null, 0,
                        new ChatStepDto.ContextInfo("catalog", "skill-catalog", null, entries, null)));
            }
        }
    }

    /** 会话历史里最近一次已下发的注入步骤(倒序;用于「内容不变不重复展示」判定)。 */
    private static ChatStepDto.ContextInfo lastEmittedContext(List<ChatStoreService.StoredMessage> history,
                                                              String stepId) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            List<ChatStepDto> steps = history.get(i).steps();
            if (steps == null) {
                continue;
            }
            for (ChatStepDto s : steps) {
                if (stepId.equals(s.id()) && s.context() != null) {
                    return s.context();
                }
            }
        }
        return null;
    }

    private static String abbreviateForSse(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…(" + text.length() + " chars)";
    }

    /**
     * 工具调用日志的 args 脱敏:凭据类字段(headers 的值、password、token 等)
     * 替换为 ***。值只传给服务层,任何日志/步骤/审批明细都不得出现明文。
     */
    /** 单条工具参数重放上限:超过则不附 rawArgs(该步退化为文本历史,防病态超长参数)。 */
    private static final int MAX_REPLAY_ARGS_CHARS = 20_000;

    /**
     * 单张图片的 token 估算。
     *
     * <p>实测(2026-09-13,同一会话逐轮对比):从 0 图到 1 图 in 增加 ~1.0K
     * (其中含少量工具结果文本);1 图到 3 图再增加 ~2.3K,即单张 ≈1K。
     * 与 data URL 长度无关(视觉分辨率口径)——74KB 的 tools 段计 ~11K tokens,
     * 而单张 2-3MB 的图片只计 ~1K。取 1K 与实测对齐;图片字节的膨胀
     * 由 recycleOldImages 单独兜底(那才是带宽问题,不是 token 问题)。
     *
     * <p>历史注:旧值 1_200 → 一度按 data URL 长度/16 估算(误读校准日志口径,
     * 见 logCalibration 注释)→ 3_000(过估,实测 ratio 0.61)。现取 1_000。
     */
    private static final int IMAGE_TOKEN_ESTIMATE = 1_000;

    /** 把脱敏后的原始参数附到 typed input 上(跨轮历史重建用;null/空/非法 = 不附)。 */
    private ChatStepDto.StepInput withRawArgs(ChatStepDto.StepInput input, String args) {
        if (input == null || args == null || args.isBlank()) {
            return input;
        }
        try {
            // 只有合法 JSON 对象才能作为 tool_calls.arguments 重放
            if (!objectMapper.readTree(args).isObject()) {
                return input;
            }
        } catch (Exception e) {
            return input;
        }
        String raw = scrubArgsForLog(args);
        if (raw.length() > MAX_REPLAY_ARGS_CHARS) {
            return input;
        }
        return new ChatStepDto.StepInput(input.sql(), input.service(), input.limit(), input.target(), raw);
    }

    private String scrubArgsForLog(String args) {
        if (args == null || args.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            if (!(node instanceof ObjectNode obj)) {
                return args;
            }
            JsonNode headers = obj.get("headers");
            if (headers != null && headers.isObject()) {
                ObjectNode masked = objectMapper.createObjectNode();
                headers.fieldNames().forEachRemaining(k -> masked.put(k, "***"));
                obj.set("headers", masked);
            }
            JsonNode env = obj.get("env");
            if (env != null && env.isObject()) {
                ObjectNode masked = objectMapper.createObjectNode();
                env.fieldNames().forEachRemaining(k -> masked.put(k, "***"));
                obj.set("env", masked);
            }
            for (String key : new String[]{"password", "token", "apiKey", "api_key", "secret"}) {
                if (obj.hasNonNull(key)) {
                    obj.put(key, "***");
                }
            }
            return obj.toString();
        } catch (Exception e) {
            return args; // 非法 JSON:原样保留(上游模型参数问题,不含结构化凭据)
        }
    }



    /**
     * 把上游返回的错误体转成一行可读信息:能解析出 {@code error.message} 就取它,
     * 否则退化为 HTTP 状态码短语——避免把整段 {@code {"error":{...}}} JSON 甩给前端/用户。
     */
    private static String friendlyUpstreamError(String body) {
        if (body == null || body.isBlank()) {
            return "无返回信息";
        }
        final String trimmed = body.trim();
        // 部分中转把上游错误体塞进 message 字段的**转义 JSON 字符串**里,形如
        // {"error":{"message":"{\"code\":11150,\"msg\":\"the reasoning effort value
        // is not supported...\"}"}}。朴素的“读到下一个引号”会在第一个 \" 处截断,
        // 只剩 "{\" —— 调用方(如档位降级判定)就看不到真正的错误文案。
        // 这里先把转义引号还原,再按嵌套结构取最内层的可读消息。
        String unescaped = trimmed.replace("\\\"", "\"");
        String inner = deepestMessage(unescaped);
        if (inner != null) {
            return withUpstreamHint(inner);
        }
        // 常见错误体形如 {"error":{"message":"...","type":"..."}}
        final int msgIdx = trimmed.indexOf("\"message\"");
        if (msgIdx >= 0) {
            int start = trimmed.indexOf(':', msgIdx) + 1;
            while (start < trimmed.length() && (trimmed.charAt(start) == ' ' || trimmed.charAt(start) == '"')) {
                start++;
            }
            int end = start;
            while (end < trimmed.length() && trimmed.charAt(end) != '"') {
                end++;
            }
            String message = end > start ? trimmed.substring(start, end) : null;
            if (message != null && !message.isBlank()) {
                return withUpstreamHint(message);
            }
        }
        return Texts.abbreviate(trimmed, 160);
    }

    /**
     * 中转站透传的 "Upstream error: N" 无信息量,补一句状态码语义。
     * 提取成共用逻辑:嵌套转义 JSON 的早返回路径与平铺路径都要带上这条提示
     * (此前早返回把该提示挤成死代码,9c346a0 修复的语义丢失)。
     */
    private static String withUpstreamHint(String message) {
        if (message.matches("(?i)upstream (error|unavailable).*")) {
            return message + "（上游模型服务暂不可用,稍后重试）";
        }
        return Texts.abbreviate(message, 160);
    }

    /**
     * 从（已还原转义引号的）错误体里取最内层可读消息。
     * 嵌套形如 {"error":{"message":"{\"code\":N,\"msg\":\"...\",\"extError\":{...}}"}}——
     * 还原后外层 message 的值本身又是一段 JSON。取最内层的 msg/message 字段才有信息量。
     * 取不到时返回 null，由调用方走原来的平铺解析。
     */
    private static String deepestMessage(String unescaped) {
        String best = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(?:msg|message)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(unescaped);
        while (m.find()) {
            String value = m.group(1).trim();
            // 跳过纯 JSON 片段（值以 { 开头说明它自己还是个对象，不是可读文案）
            if (value.isEmpty() || value.startsWith("{") || value.startsWith("[")) continue;
            best = value;
        }
        return best;
    }

    /** Callbacks for the SSE events of one chat turn. */
    public interface ChatEventConsumer {

        void step(ChatStepDto step);

        void delta(String token);

        default void reasoningDelta(Integer roundIndex, String token) {
        }

        /** 即将向上游发出一次模型请求:消费方用它刷新推理计时锚点(排除 RAG/装配耗时)。 */
        default void upstreamRequestStarted() {
        }

        default void approvalRequired(ApprovalRequestDto request) {
        }

        void sources(List<CitationDto> citations);
    }

    /**
     * Result of one chat turn. {@code promptTokens} 是最后一次请求的 token
     * 估算、{@code contextWindow} 是生效窗口——两者随 done 下发给前端进度条。
     */
    public record ChatTurn(String answer, List<CitationDto> citations, TokenUsage usage,
                           Long contextWindow, Integer promptTokens, Long ttftMs) {
    }

    /** Wire-format message wrapper (JsonNode so tool messages mix in). */
    private record WireMessage(ObjectNode node) {

        static WireMessage system(ObjectMapper mapper, String content) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "system");
            n.put("content", content);
            return new WireMessage(n);
        }

        static WireMessage user(ObjectMapper mapper, String content) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "user");
            n.put("content", content);
            return new WireMessage(n);
        }

        static WireMessage assistant(ObjectMapper mapper, String content) {
            ObjectNode n = mapper.createObjectNode();
            n.put("role", "assistant");
            n.put("content", content);
            return new WireMessage(n);
        }
    }
}
