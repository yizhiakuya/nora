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
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
    /** 模型能力降级注册表(进程内记忆,2026-09-17 拆分 Step 3)。 */
    private final ModelCapabilityRegistry capabilityRegistry;
    /** LLM 上游流式客户端(2026-09-17 拆分 Step 3)。 */
    private final UpstreamLlmClient upstreamClient;
    /** 模型/渠道解析器(2026-09-17 拆分 Step 3)。 */
    private final ModelResolver modelResolver;
    /** 上下文装配器(从本类拆出,2026-09-17 复杂度审计 Step 4)。 */
    private final ChatContextAssembler contextAssembler;
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
        this.capabilityRegistry = new ModelCapabilityRegistry();
        this.upstreamClient = new UpstreamLlmClient(objectMapper, capabilityRegistry);
        this.modelResolver = new ModelResolver(llmProperties, modelProviderService);
        this.contextAssembler = new ChatContextAssembler(objectMapper, agentWorkspaceService,
                agentSkillService, toolsSpecBuilder);
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
        ResolvedLlm resolved = modelResolver.resolve(requestedModel, requestedReasoningLevel, providerId);
        if (resolved == null) {
            // configured() 已校验过,这里防御性兜底
            throw new IllegalStateException("no LLM provider resolved for model: " + requestedModel);
        }
        // 系统性上下文预算(设计见 docs/context-management-design.md):
        // 会话级裁剪 + 轮内微压缩 + 超限恢复,统一走 ContextBudget
        ContextBudget budget = new ContextBudget(resolved.contextWindow());
        ChatContextAssembler.SystemPromptResult[] promptOut = new ChatContextAssembler.SystemPromptResult[1];
        List<WireMessage> messages = contextAssembler.buildMessages(userMessage, history, citations, reflections, budget, promptOut);
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
                long overhead = contextAssembler.requestOverheadTokens();
                int compactedCount = contextAssembler.compactForRound(messages, budget, false, overhead);
                if (compactedCount > 0 || contextAssembler.lastRecycledImages() > 0) {
                    StringBuilder what = new StringBuilder();
                    if (compactedCount > 0) what.append("已压缩 ").append(compactedCount).append(" 条早期工具结果");
                    if (contextAssembler.lastRecycledImages() > 0) {
                        if (what.length() > 0) what.append("、");
                        what.append("已回收 ").append(contextAssembler.lastRecycledImages()).append(" 条消息的早期图片附件");
                    }
                    eventConsumer.step(new ChatStepDto("s-compact-" + round, "think",
                            "整理上下文", what + (compactedCount > 0 ? ",释放约 " + contextAssembler.estimateTokensFreed() + " tokens 预算" : ""),
                            0L, "completed", null, null, null, round));
                }
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                        + (int) overhead;
                StreamTurnResult result = streamTurn(messages, requestedModel, reasoningLevel,
                        round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                if (result.usage() != null) {
                    totalUsage = totalUsage == null ? result.usage() : totalUsage.add(result.usage());
                }
                if (result.failed()) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 取消:中断被上游阻塞读转成 failed 结果(streamUpstream 已恢复标志),
                        // 这里短路——绝不当「空响应」重试,否则取消变成多烧一整轮 token
                        log.info("tool round interrupted (user cancel), abort turn");
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                    }
                    StreamTurnResult retry = null;
                    boolean explicitVisionReject = ModelCapabilityRegistry.isVisionUnsupported(result.errorMessage());
                    boolean maybeVisionReject = !explicitVisionReject
                            && hasImagesInMessages(messages) && ModelCapabilityRegistry.isGenericUpstream400(result.errorMessage());
                    if (explicitVisionReject || maybeVisionReject) {
                        // 模型不支持识图(上游拒绝图片):剥离图片后重试。
                        // 中转流式下的 400 只有模糊文案(maybeVisionReject),因此以“剥图后重试是否成功”
                        // 作为最终判据:成功才记住结论,后续轮次直接不再附图(“不支持就不去看”)。
                        int stripped = stripImagesFromMessages(messages);
                        log.info("vision retry for {} ({}): stripped {} image(s), explicit={}",
                                resolved.model(), resolved.baseUrl(), stripped, explicitVisionReject);
                        StreamTurnResult visionRetry = streamTurn(messages, requestedModel, reasoningLevel,
                                round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                        if (!visionRetry.failed()) {
                            // 确认:这个模型确实看不了图(下一次直接不发图片)
                            capabilityRegistry.markVisionRejected(resolved);
                            eventConsumer.step(new ChatStepDto("s-vision-" + round, "think", "模型不支持识图",
                                    "已自动跳过图片内容继续回答（可在设置中切换支持识图的模型）",
                                    0L, "completed", null, null, null, round + 1));
                        }
                        retry = visionRetry;
                    } else if (ChatContextAssembler.isContextOverflow(result.errorMessage())) {
                        // 上下文超限:硬压缩到恢复线(窗口 60%)后重试一次
                        contextAssembler.compactForRound(messages, budget, true, overhead);
                        lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                                + (int) overhead;
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                    } else if (ModelCapabilityRegistry.isReasoningEffortUnsupported(result.errorMessage())
                            && reasoningLevel != null && !reasoningLevel.isBlank()) {
                        // 上游不认这个档位(gemini-3.5-flash 等报 invalid_reasoning_effort):
                        // 剥掉档位重试一次——思考等级是增强项,不该让整轮对话失败。
                        // 重试期间同时挂上「该档位被拒」与「该模型不注入档位」两条结论:
                        // 后者让重试真正不带 reasoning_effort(传 null 档位时 gpt-5 家族
                        // 会回落注入 medium,只挂前者重试就不是"剥掉");
                        // 重试成功才保留结论(后续同档位请求不再撞 400,且换成别的档位
                        // 仍会正常尝试注入);失败则撤销(瞬时错误不拉黑)。
                        capabilityRegistry.markEffortRejected(resolved);
                        capabilityRegistry.markEffortStripped(resolved);
                        log.info("reasoning_effort rejected by {} ({}), retrying without it",
                                resolved.model(), resolved.baseUrl());
                        retry = streamTurn(messages, requestedModel, null, round + 1, eventConsumer, ttftMs, turnStartMs, providerId);
                        if (!retry.failed()) {
                            eventConsumer.step(new ChatStepDto("s-effort-" + round, "think", "模型不支持该思考等级",
                                    "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
                                    0L, "completed", null, null, null, round + 1));
                        } else {
                            capabilityRegistry.clearEffortRejected(resolved);
                            capabilityRegistry.clearEffortStripped(resolved);
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
                        if (!retry.failed()) {
                            result = retry;
                        }
                    }
                }
                if (result.failed()) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 流中取消(已转发部分内容):不发 s-error step,直接按取消收场
                        log.info("tool round interrupted (user cancel) with partial content, abort turn");
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("turn cancelled"));
                    }
                    eventConsumer.step(new ChatStepDto("s-error", "think", "模型返回空响应",
                            result.errorMessage() != null ? result.errorMessage() : "上游未返回内容,请重试",
                            null, "failed", null, null, null, round + 1));
                    break;
                }
                if (result.toolCalls().isEmpty()) {
                    // 回答(与推理)已随流逐 token 转发完毕
                    // 校准用**当轮** usage:传累计值会与单轮估算相除产生虚高比值
                    contextAssembler.logCalibration(lastPromptEstimate[0], result.usage(), budget);
                    return CompletableFuture.completedFuture(new ChatTurn(result.content(), citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
                }
                messages.add(new WireMessage(result.assistantMessage()));
                for (JsonNode call : result.toolCalls()) {
                    String callId = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String args = call.path("function").path("arguments").asText("{}");
                    String toolStepId = "s-call-" + callFingerprints.size() + "-" + callId;
                    emitToolStep(toolStepId, name, args, callFingerprints, messages, callId,
                            round + 1, permissionMode, sessionId, resolved, eventConsumer);
                    String lastResult = contextAssembler.lastToolResult(messages);
                    if (lastResult != null) {
                        toolOutcome = lastResult;
                    }
                }
            }
        } catch (Exception e) {
            // 取消穿透工具循环(审批等待 join 被中断抛 CompletionException(InterruptedException)
            // 且消费掉标志;或流内异常带中断 cause):不回落强制回答,直接按取消收场
            if (Texts.isInterruption(e) || Thread.currentThread().isInterrupted()) {
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
        long answerOverhead = contextAssembler.requestOverheadTokens();
        lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                + (int) answerOverhead;
        StreamTurnResult finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
        // 瞬时上游错误(空内容失败)自动重试一次;超限先硬压缩再重试。
        // 线程被中断(用户取消)绝不重试——那会让取消多烧一整轮上游 token
        if (finalResult.failed() && finalResult.content().isBlank() && !Thread.currentThread().isInterrupted()) {
            if (ChatContextAssembler.isContextOverflow(finalResult.errorMessage())) {
                contextAssembler.compactForRound(messages, budget, true, answerOverhead);
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatContextAssembler::messageTokens).sum()
                        + (int) answerOverhead;
                finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
            } else if (ModelCapabilityRegistry.isReasoningEffortUnsupported(finalResult.errorMessage())
                    && reasoningLevel != null && !reasoningLevel.isBlank()) {
                // 工具循环在首个上游请求前异常退出(catch 兜底走强制回答)时,这一轮
                // 是本轮第一个上游请求:坏档位要在这里也能降级,否则整轮失败且每轮
                // 复现。与工具轮同款:挂两条结论(档位被拒 + 该模型不注入档位)让重试
                // 真正不带 reasoning_effort,重试成功才保留。
                capabilityRegistry.markEffortRejected(resolved);
                capabilityRegistry.markEffortStripped(resolved);
                log.info("reasoning_effort rejected on final answer for {} ({}), retrying without it",
                        resolved.model(), resolved.baseUrl());
                finalResult = streamFinalAnswer(messages, requestedModel, null,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
                if (!finalResult.failed()) {
                    eventConsumer.step(new ChatStepDto("s-effort-final", "think", "模型不支持该思考等级",
                            "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
                            0L, "completed", null, null, null, reasoningRound));
                } else {
                    capabilityRegistry.clearEffortRejected(resolved);
                    capabilityRegistry.clearEffortStripped(resolved);
                }
            } else {
                finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                        reasoningRound, eventConsumer, ttftMs, turnStartMs, providerId);
            }
        }
        if (!finalResult.failed()) {
            String answerText = finalResult.content();
            if (finalResult.usage() != null) {
                totalUsage = totalUsage == null ? finalResult.usage() : totalUsage.add(finalResult.usage());
            }
            if (!answerText.isBlank()) {
                contextAssembler.logCalibration(lastPromptEstimate[0], finalResult.usage(), budget);
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
                finalResult.errorMessage(), System.currentTimeMillis() - answerStart, "failed",
                null, null, null, null));
        return CompletableFuture.failedFuture(
                new IllegalStateException(finalResult.errorMessage() != null ? finalResult.errorMessage() : "LLM stream failed"));
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
        boolean canSee = wantImages && capabilityRegistry.visionAllowed(llm, images.size());
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
        ResolvedLlm llm = ResolvedLlm.withLevel(modelResolver.resolve(requestedModel, null, providerId), "none");
        if (llm == null) {
            return null;
        }
        try {
            ObjectNode body = upstreamClient.baseBody(true, llm);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", TITLE_SYSTEM_PROMPT);
            String excerpt = userMessage.length() <= TITLE_EXCERPT_CHARS
                    ? userMessage
                    : userMessage.substring(0, TITLE_EXCERPT_CHARS);
            messages.addObject().put("role", "user").put("content", excerpt);
            // 不带 tools：标题任务不允许调用工具
            StreamTurnResult result = upstreamClient.streamUpstream(llm, body, (content, reasoning) -> {
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
        return modelResolver.resolve(requestedModel) != null;
    }

    /** Variant honouring an explicit provider id (渠道精确解析)。 */
    public boolean configured(String requestedModel, Long providerId) {
        return modelResolver.resolve(requestedModel, null, providerId) != null;
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
        ResolvedLlm llm = ResolvedLlm.withLevel(modelResolver.resolve(requestedModel, null, providerId), reasoningLevel);
        ObjectNode body = upstreamClient.baseBody(true, llm);
        body.set("messages", upstreamClient.messagesArray(messages));
        body.set("tools", toolsSpec());
        // 请求即将发出:刷新消费方的推理计时锚点(RAG/装配时间不计入思考时长)
        eventConsumer.upstreamRequestStarted();
        return upstreamClient.streamUpstream(llm, body, (contentToken, reasoningToken) -> {
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
        ResolvedLlm llm = ResolvedLlm.withLevel(modelResolver.resolve(requestedModel, null, providerId), reasoningLevel);
        ObjectNode body = upstreamClient.baseBody(true, llm);
        body.set("messages", upstreamClient.messagesArray(messages));
        body.set("tools", toolsSpec());
        // final round: the model must answer, not call more tools
        body.put("tool_choice", "none");
        eventConsumer.upstreamRequestStarted();
        return upstreamClient.streamUpstream(llm, body, (contentToken, reasoningToken) -> {
            if (ttftMs[0] < 0 && (contentToken != null || reasoningToken != null)) {
                ttftMs[0] = System.currentTimeMillis() - turnStartMs;
            }
            if (reasoningToken != null) eventConsumer.reasoningDelta(round, reasoningToken);
            if (contentToken != null) eventConsumer.delta(contentToken);
        });
    }





    /** chat.completions body's messages array as WireMessage list (responses conversion helper). */


    /**
     * Per-provider extra HTTP headers. OpenCode's free tier rejects requests
     * without an X-Session-ID ("free tier can only be used in OpenCode");
     * the ID is derived from the API key so it stays stable across turns
     * (provider-side session continuity) without leaking anything.
     */
    /** DEBUG 报文日志:协议/模型/档位/URL/请求体(api_key 不入日志,Authorization 头不拼进 body)。 */

    /**
     * Provider 专属附加头(opencode 需要 X-Session-ID)。
     * 空数组时必须跳过 {@code HttpRequest.Builder.headers(String...)}:
     * JDK 对 0 个参数同样抛 IAE "wrong number, 0, of parameters"(varargs
     * 成对校验不接受空数组),曾在所有普通 provider 上导致每轮请求必失败。
     */





    /** OpenAI reasoning_effort 合法值(截至今日上游公开档位)。 */

    /**
     * 是否支持/需要 reasoning_effort 透传的模型家族:
     * OpenAI(gpt-5/o)、Anthropic thinking(claude-*-thinking,实测 2026-09-05:
     * 不带 reasoning_effort 时中转站不返回 reasoning_content)、
     * 中转站档位后缀命名(gemini-*-high 等)。
     */

    /** Reads the common reasoning fields emitted by OpenAI-compatible relays. */



    /** tools spec 装配委托(实现见 ChatToolsSpec,2026-09-17 拆分)。 */
    private ArrayNode toolsSpec() {
        return toolsSpecBuilder.build();
    }













    /*
     * 注:不再单列"协议封装"常量。实测(空会话单请求)76K body → in=11550,
     * 其中消息体仅 ~2K 字符——即 74K 的 tools 段实际承担了 ~11K tokens,
     * 已含协议封装/系统字段。再叠加一个固定常量会重复计算(实测 ratio 0.61)。
     */











    /**
     * 下发「注入上下文」步骤(dsh 模式:注入内容对用户可见,自描述 form 声明信息形态)。
     * 工作区引导 → form=instructions;技能目录 → form=catalog。无注入时静默跳过。
     *
     * <p>降噪(2026-09-12):注入本身每轮都发生(系统提示必须随每次请求携带),
     * 但步骤只在<b>首轮或内容较上次下发有变化</b>时下发——与历史里最近一次同名步骤
     * 逐字段对比,一致则跳过(不变的重复展示只是噪音)。内容变化(如 agent 自演化
     * 编辑了记忆文件)仍会重新下发,保证「模型这轮读到的记忆变过」对用户可见。
     */
    private void emitContextSteps(ChatContextAssembler.SystemPromptResult prompt, List<ChatStoreService.StoredMessage> history,
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


    /**
     * 工具调用日志的 args 脱敏:凭据类字段(headers 的值、password、token 等)
     * 替换为 ***。值只传给服务层,任何日志/步骤/审批明细都不得出现明文。
     */
    /** 单条工具参数重放上限:超过则不附 rawArgs(该步退化为文本历史,防病态超长参数)。 */
    private static final int MAX_REPLAY_ARGS_CHARS = 20_000;


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
}
