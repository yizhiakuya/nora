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
import org.springframework.web.client.RestClient;

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
 *   <li>Tool loop: non-streaming rounds where the model may call
 *       {@code execute_sql} / {@code read_service_logs}; each call emits a
 *       structured step (toolName + parsed input + typed result) and feeds
 *       the bounded output back to the model (configurable max rounds,
 *       default 10)</li>
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
            2. 需要真实数据或执行操作时,直接调用相应工具,不要凭记忆编造,也不要只描述计划而不行动;
               工具返回错误时如实说明。
            3. 你的语气、风格与工作习惯定义在下方工作区的 SOUL.md 与 AGENTS.md 中——它们是
               你的可演化指令:self-evolution 是预期行为,想调整行为方式时直接编辑对应文件。
            4. 用户说「记住…」时必须写入工作区文件落盘,不能只口头答应。
            5. 最终回答使用自然的 Markdown:段落紧凑、结论前置;不要复述工具参数或执行过程。
            """;

    /** Default max tool rounds per chat turn (ReAct depth guard). */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 10;

    /**
     * Tool output budget per harness research: success keeps a large inline
     * budget, failure gets a head+tail excerpt (failures are diagnosable from
     * the ends alone and never need a persistence pointer).
     */
    static final int MAX_SUCCESS_CHARS = 30_000;
    static final int MAX_FAILURE_CHARS = 10_000;
    /** How much of the head/tail a failure excerpt keeps. */
    private static final int FAILURE_HEAD_CHARS = 6_000;
    private static final int FAILURE_TAIL_CHARS = 3_000;

    /** Repeats of the same (tool, args) fingerprint before the loop breaker trips. */
    private static final int LOOP_WARN_THRESHOLD = 2;
    private static final int LOOP_BLOCK_THRESHOLD = 3;

    private final LlmProperties llmProperties;
    private final RagRetrievalClient ragRetrievalClient;
    private final SqlToolClient sqlToolClient;
    private final ServiceLogClient serviceLogClient;
    private final ObjectMapper objectMapper;
    private final RestClient llmClient;
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
                                    @org.springframework.beans.factory.annotation.Value("${nora.agent.max-tool-rounds:10}") int maxToolRounds,
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
        this.maxToolRounds = Math.max(1, maxToolRounds);
        this.proxyProperties = proxyProperties != null ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();
        // 显式超时:上游中转对带长 tool 消息的请求可能长时间不响应,
        // 默认无超时的 RestClient 会永远挂起整轮对话
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.llmClient = RestClient.builder()
                .baseUrl(llmProperties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null,
                null, null, null, null, null, null, null, null, null, DEFAULT_MAX_TOOL_ROUNDS, null);
    }

    /** Test entry: explicit max tool rounds, no provider store. */
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    int maxToolRounds) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null,
                null, null, null, null, null, null, null, null, null, maxToolRounds, null);
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
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (userMessage.length() > 8000) {
            throw new IllegalArgumentException("message exceeds 8000 characters");
        }
        if (!configured(requestedModel)) {
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
        ResolvedLlm resolved = resolveLlm(requestedModel, requestedReasoningLevel);
        if (resolved == null) {
            // configured() 已校验过,这里防御性兜底
            throw new IllegalStateException("no LLM provider resolved for model: " + requestedModel);
        }
        // 系统性上下文预算(设计见 docs/context-management-design.md):
        // 会话级裁剪 + 轮内微压缩 + 超限恢复,统一走 ContextBudget
        ContextBudget budget = new ContextBudget(resolved.contextWindow());
        List<WireMessage> messages = buildMessages(userMessage, history, citations, reflections, budget);
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
                int compactedCount = compactForRound(messages, budget, false, PER_REQUEST_OVERHEAD_TOKENS);
                if (compactedCount > 0) {
                    eventConsumer.step(new ChatStepDto("s-compact-" + round, "think",
                            "整理上下文", "已压缩 " + compactedCount + " 条早期工具结果,释放约 "
                                    + estimateTokensFreed + " tokens 预算",
                            0L, "completed", null, null, null, round));
                }
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                        + PER_REQUEST_OVERHEAD_TOKENS;
                StreamTurnResult result = streamTurn(messages, requestedModel, reasoningLevel,
                        round + 1, eventConsumer, ttftMs, turnStartMs);
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
                    if (isContextOverflow(result.errorMessage)) {
                        // 上下文超限:硬压缩到恢复线(窗口 60%)后重试一次
                        compactForRound(messages, budget, true, PER_REQUEST_OVERHEAD_TOKENS);
                        lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum();
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs);
                    } else if (result.content().isEmpty() && result.toolCalls().isEmpty()) {
                        // 中转渠道偶发 4xx/5xx(无内容返回):自动重试一次,重试仍失败才放弃本轮。
                        // 已有部分内容/工具调用的失败不重试(内容已随流转发,重发会重复输出)
                        retry = streamTurn(messages, requestedModel, reasoningLevel, round + 1, eventConsumer, ttftMs, turnStartMs);
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
                    logCalibration(lastPromptEstimate[0], totalUsage, budget);
                    return CompletableFuture.completedFuture(new ChatTurn(result.content, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
                }
                messages.add(new WireMessage(result.assistantMessage));
                for (JsonNode call : result.toolCalls) {
                    String callId = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String args = call.path("function").path("arguments").asText("{}");
                    String toolStepId = "s-call-" + callFingerprints.size() + "-" + callId;
                    emitToolStep(toolStepId, name, args, callFingerprints, messages, callId,
                            round + 1, permissionMode, sessionId, eventConsumer);
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
        lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                + PER_REQUEST_OVERHEAD_TOKENS;
        StreamTurnResult finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                reasoningRound, eventConsumer, ttftMs, turnStartMs);
        // 瞬时上游错误(空内容失败)自动重试一次;超限先硬压缩再重试。
        // 线程被中断(用户取消)绝不重试——那会让取消多烧一整轮上游 token
        if (finalResult.failed && finalResult.content().isBlank() && !Thread.currentThread().isInterrupted()) {
            if (isContextOverflow(finalResult.errorMessage)) {
                compactForRound(messages, budget, true, PER_REQUEST_OVERHEAD_TOKENS);
                lastPromptEstimate[0] = messages.stream().mapToInt(ChatOrchestrationService::messageTokens).sum()
                        + PER_REQUEST_OVERHEAD_TOKENS;
            }
            finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                    reasoningRound, eventConsumer, ttftMs, turnStartMs);
        }
        if (!finalResult.failed) {
            String answerText = finalResult.content;
            if (finalResult.usage() != null) {
                totalUsage = totalUsage == null ? finalResult.usage() : totalUsage.add(finalResult.usage());
            }
            if (!answerText.isBlank()) {
                logCalibration(lastPromptEstimate[0], totalUsage, budget);
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
                ? "查询结果：\n```\n" + abbreviate(fallbackOutcome, MAX_FAILURE_CHARS) + "\n```"
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
        ParsedArgs parsed = parseArgs(name, args);
        ChatStepDto.StepInput input = parsed.input();
        // Claude Code pattern: the model fills the display title via the
        // description arg (imperative, no subjective words); fall back to
        // the tool name when it omits one
        String title = parsed.description() != null && !parsed.description().isBlank()
                ? parsed.description() : defaultTitle(name);
        long toolStart = System.currentTimeMillis();
        log.info("tool call: {} (round={}, args={})", name, roundIndex, abbreviate(args == null ? "{}" : args, 200));
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                null, null, "running", name, input, null, roundIndex));

        // loop breaker: same (tool, normalized args) repeated too often.
        // MCP 工具参数 schema 千差万别,typed input 抽不出共同字段——指纹直接用
        // 原始 args,避免不同参数被误判为重复调用;manage_workspace/manage_skill
        // 同理(主体在 content/path/instructions 字段,typed input 抽不到)。
        boolean rawFingerprint = name.startsWith("mcp__")
                || "manage_workspace".equals(name) || "manage_skill".equals(name);
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

        ToolOutcome outcome = executeTool(name, args, parsed);
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
        backfillToolMessage(messages, callId, outcome.content());
    }

    private void finishToolStep(String toolStepId, String name, String title, ChatStepDto.StepInput input,
                                long toolStart, ChatStepDto.StepResult result, String status,
                                int roundIndex,
                                ChatEventConsumer eventConsumer) {
        String summaryLine = result.summary() != null ? result.summary()
                : (result.error() != null ? abbreviate(result.error(), 160) : null);
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                summaryLine, System.currentTimeMillis() - toolStart, status, name, input, result, roundIndex));
    }

    /** Appends the tool result message fed back to the model (RespondToModel style: errors are content, not exceptions). */
    private void backfillToolMessage(List<WireMessage> messages, String callId, String content) {
        ObjectNode toolMsg = objectMapper.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", callId);
        toolMsg.put("content", content);
        messages.add(new WireMessage(toolMsg));
    }

    private String lastToolResult(List<WireMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            WireMessage m = messages.get(i);
            if ("tool".equals(m.node().path("role").asText())) {
                return m.node().path("content").asText(null);
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
                case "manage_skill" -> {
                    // read/update/remove 用 target(名称或 id);create 用 name
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("name").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
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
        if ("manage_workspace".equals(name) || "manage_skill".equals(name)) {
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
                        ? " | 内容预览: " + abbreviate(a.path("content").asText(""), 200) : "");
                risk = "该路径在工作区之外——将改动本机的真实文件(不可自动撤销)";
            }
            default -> {
                if (toolName.startsWith("mcp__")) {
                    actionType = "mcp_tool";
                    // 畸形挂载名(LLM 幻觉出 mcp__srv 缺第二个 __)不能让 substring 越界
                    int sep = toolName.indexOf("__", "mcp__".length());
                    String serverName = sep < 0 ? "?" : toolName.substring("mcp__".length(), sep);
                    target = "MCP 服务器 " + serverName;
                    detail = "工具: " + toolName + "\n参数: " + abbreviate(rawArgs == null ? "{}" : rawArgs, 400);
                    risk = "外部 MCP 服务器提供的工具,能力未知,执行前需确认";
                } else {
                    actionType = toolName;
                    target = parsed.input().service() != null ? parsed.input().service()
                            : (parsed.input().sql() != null ? abbreviate(parsed.input().sql(), 40) : "—");
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

    public boolean configured(String requestedModel) {
        return resolveLlm(requestedModel) != null;
    }

    private ResolvedLlm resolveLlm(String requestedModel) {
        return resolveLlm(requestedModel, null);
    }

    /**
     * 解析执行端点与思考等级:请求级等级 > 设置页该模型默认等级 > auto。
     * 该模型的 reasoningLevels 白名单同时约束请求级取值(不在白名单内则回落默认)。
     */
    private ResolvedLlm resolveLlm(String requestedModel, String requestedReasoningLevel) {
        // 设置中心(数据库 provider store)优先:模型选择/思考等级/每模型协议都源于此。
        // 静态 nora.llm.* 配置仅作兜底(全新部署还没配 provider 时可用),
        // 否则环境变量一存在就会短路整个 provider 体系——UI 上怎么选模型都不生效。
        if (modelProviderService != null) {
            ResolvedLlm fromStore = resolveFromStore(requestedModel, requestedReasoningLevel);
            if (fromStore != null) return fromStore;
        }
        if (llmProperties.configured()) {
            return new ResolvedLlm(llmProperties.baseUrl(), llmProperties.apiKey(), llmProperties.model(), "openai",
                    null, null);
        }
        return null;
    }

    /** Provider-store leg of {@link #resolveLlm}; null when nothing usable is enabled. */
    private ResolvedLlm resolveFromStore(String requestedModel, String requestedReasoningLevel) {
        ModelProviderService.ActiveProvider provider = modelProviderService.activeProvider(requestedModel);
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
                perModel.contextWindow());
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

    private RestClient clientFor(ResolvedLlm llm) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        return RestClient.builder().baseUrl(llm.baseUrl()).requestFactory(factory).build();
    }

    /** One executed tool call: bounded content plus UI-facing metadata. */
    record ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated) {
    }

    /**
     * Resolves a managed source name (or numeric id) to its env-service id.
     * Names are matched case-insensitively against the full registry
     * (including paused sources, which /services omits).
     */
    private Long resolveManagedSourceId(String target) {
        String listing = serviceManageClient.list();
        if (listing.startsWith("ERROR") || listing.startsWith("(")) {
            return null;
        }
        String trimmed = target.trim();
        for (String line : listing.split("\n")) {
            // 行形如:id=3 | DOCKER | nora-redis | nora-redis | enabled
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            String id = parts[0].replace("id=", "").trim();
            String kind = parts[1].trim();
            String name = parts[2].trim();
            if (trimmed.equalsIgnoreCase(name) || (trimmed.matches("\\d+") && trimmed.equals(id))) {
                return Long.parseLong(id);
            }
            if ("PROC".equals(kind) && trimmed.equalsIgnoreCase(name)) {
                return Long.parseLong(id);
            }
        }
        return null;
    }

    /** create 结果的摘要行(密码永不回传,渲染端只含连接目标与测试结论)。 */
    private static String summarizeCreate(String content) {
        String firstLine = content.contains("\n") ? content.substring(0, content.indexOf('\n')) : content;
        return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 80);
    }

    /**
     * Dispatches a tool call. Guardrail rejections return a three-part error
     * (what was refused + which rule + a correct example) so the model can
     * self-correct on the next round.
     */
    private ToolOutcome executeTool(String name, String args, ParsedArgs parsed) {
        if ("execute_sql".equals(name)) {
            String sql = parsed.input().sql() != null ? parsed.input().sql() : "";
            String guard = guardSql(sql);
            if (guard != null) {
                return new ToolOutcome("ERROR: " + guard, null, null, false);
            }
            SqlToolClient.SqlOutcome outcome = sqlToolClient.executeSqlDetailed(sql, parsed.input().target());
            return new ToolOutcome(outcome.content(), outcome.summary(), null, outcome.truncated());
        }
        if ("read_service_logs".equals(name)) {
            String service = parsed.input().service() != null ? parsed.input().service() : "";
            int limit = parsed.input().limit() != null ? Math.min(parsed.input().limit(), 100) : 50;
            String guard = guardService(service);
            if (guard != null) {
                return new ToolOutcome("ERROR: " + guard, null, null, false);
            }
            return bounded(serviceLogClient.readLogs(service, limit), null);
        }
        if ("execute_write_sql".equals(name)) {
            String sql = parsed.input().sql() != null ? parsed.input().sql() : "";
            String guard = RiskClassifier.validateWriteSql(sql);
            if (guard != null) {
                return new ToolOutcome("ERROR: " + guard, null, null, false);
            }
            if (writeSqlClient == null) {
                return new ToolOutcome("ERROR: 写入能力未启用(服务未配置)", null, null, false);
            }
            String content = writeSqlClient.executeWrite(sql, parsed.input().target());
            boolean failure = content.startsWith("ERROR:");
            return new ToolOutcome(content, failure ? null : content, null, false);
        }
        if ("manage_container".equals(name)) {
            String service = parsed.input().service() == null ? "" : parsed.input().service();
            String guard = RiskClassifier.validateContainerAction(parsed.containerAction());
            if (guard != null) {
                return new ToolOutcome("ERROR: " + guard, null, null, false);
            }
            String guardService = guardService(service);
            if (guardService != null) {
                return new ToolOutcome("ERROR: " + guardService, null, null, false);
            }
            if (!serviceLogClient.listServices().contains(service)) {
                return new ToolOutcome("ERROR: unknown service " + service + ". 可用服务必须来自环境服务注册表", null, null, false);
            }
            if (containerControlClient == null) {
                return new ToolOutcome("ERROR: 容器控制能力未启用(服务未配置)", null, null, false);
            }
            String content = containerControlClient.control(service, parsed.containerAction());
            boolean failure = content.startsWith("ERROR:");
            return new ToolOutcome(content, failure ? null : content, null, false);
        }
        if ("manage_datasource".equals(name)) {
            String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
            String guard = RiskClassifier.validateDatasourceAction(action);
            if (guard != null) {
                return new ToolOutcome("ERROR: " + guard, null, null, false);
            }
            if ("list".equals(action)) {
                return bounded(dataSourceManageClient.list(), null);
            }
            if ("schema".equals(action)) {
                // schema 不在工具 spec 里宣传,但模型从 list 结果推断时放行(只读)
                return bounded(dataSourceManageClient.schema(parsed.input().target()), null);
            }
            if ("create".equals(action)) {
                // 连接参数从原始 args 取(密码只 here 使用,不进 ParsedArgs/步骤记录)
                JsonNode a;
                try {
                    a = objectMapper.readTree(args == null ? "{}" : args);
                } catch (Exception e) {
                    return new ToolOutcome("ERROR: 参数不是合法 JSON: " + e.getMessage(), null, null, false);
                }
                String dName = a.path("name").asText(null);
                String engine = a.path("engine").asText(null);
                String host = a.path("host").asText(null);
                Integer port = a.path("port").isInt() ? a.path("port").asInt() : null;
                String database = a.path("database").asText(null);
                String username = a.path("username").asText(null);
                String password = a.path("password").asText(null);
                String createGuard = RiskClassifier.validateDatasourceCreate(engine, host, port, database);
                if (createGuard != null) {
                    return new ToolOutcome("ERROR: " + createGuard, null, null, false);
                }
                if (dName == null || dName.isBlank()) {
                    return new ToolOutcome("ERROR: 缺少 name 参数(连接显示名,如 \"订单库-生产\")", null, null, false);
                }
                String content = dataSourceManageClient.create(dName, engine, host, port, database, username, password);
                boolean failure = content.startsWith("ERROR:");
                return new ToolOutcome(content, failure ? null : summarizeCreate(content), null, false);
            }
            // test / remove:目标 = name 或数字 id
            String target = parsed.input().target();
            if (target == null || target.isBlank()) {
                return new ToolOutcome("ERROR: 缺少目标数据源(name 或 id)。可先用 action=list 查看", null, null, false);
            }
            Long connectionId = sqlToolClient.resolveConnectionId(target);
            if (connectionId == null) {
                return new ToolOutcome("ERROR: 找不到数据源 \"" + target + "\"。可用连接:\n" + dataSourceManageClient.list(),
                        null, null, false);
            }
            String content = "test".equals(action)
                    ? dataSourceManageClient.test(connectionId)
                    : dataSourceManageClient.remove(connectionId);
            boolean failure = content.startsWith("ERROR:");
            return new ToolOutcome(content, failure ? null : content, null, false);
        }
        if ("manage_service".equals(name)) {
            String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
            String guard = RiskClassifier.validateServiceAction(action);
            if (guard != null) {
                return new ToolOutcome("ERROR: " + guard, null, null, false);
            }
            if ("list".equals(action)) {
                return bounded(serviceManageClient.list(), null);
            }
            if ("register".equals(action)) {
                JsonNode a;
                try {
                    a = objectMapper.readTree(args == null ? "{}" : args);
                } catch (Exception e) {
                    return new ToolOutcome("ERROR: 参数不是合法 JSON: " + e.getMessage(), null, null, false);
                }
                String kind = a.path("kind").asText(null);
                String sName = a.path("name").asText(null);
                String fileLogPath = a.path("fileLogPath").asText(null);
                String containerName = a.path("containerName").asText(null);
                String command = a.path("command").asText(null);
                String workDir = a.path("workDir").asText(null);
                String registerGuard = RiskClassifier.validateServiceRegister(kind, fileLogPath, containerName, command);
                if (registerGuard != null) {
                    return new ToolOutcome("ERROR: " + registerGuard, null, null, false);
                }
                if (sName == null || sName.isBlank()) {
                    return new ToolOutcome("ERROR: 缺少 name 参数(纳管源显示名)", null, null, false);
                }
                String content = serviceManageClient.register(kind, sName, fileLogPath, containerName, command, workDir);
                boolean failure = content.startsWith("ERROR:");
                return new ToolOutcome(content, failure ? null : content, null, false);
            }
            // enable / disable / remove:目标 = name 或数字 id
            String target = parsed.input().target();
            if (target == null || target.isBlank()) {
                return new ToolOutcome("ERROR: 缺少目标纳管源(name 或 id)。可先用 action=list 查看", null, null, false);
            }
            Long sourceId = resolveManagedSourceId(target);
            if (sourceId == null) {
                return new ToolOutcome("ERROR: 找不到纳管源 \"" + target + "\"。可用纳管源:\n" + serviceManageClient.list(),
                        null, null, false);
            }
            String content = switch (action) {
                case "enable" -> serviceManageClient.setEnabled(sourceId, true);
                case "disable" -> serviceManageClient.setEnabled(sourceId, false);
                default -> serviceManageClient.remove(sourceId);
            };
            boolean failure = content.startsWith("ERROR:");
            return new ToolOutcome(content, failure ? null : content, null, false);
        }
        if ("read_file".equals(name)) {
            String action = parsed.datasourceAction() == null ? "list" : parsed.datasourceAction().trim().toLowerCase();
            if ("list".equals(action) || parsed.input().target() == null) {
                // 无 id = 列出文件让模型挑;显式 action=list 同理
                return bounded(fileToolClient.list(), null);
            }
            String target = parsed.input().target();
            if (!target.matches("\\d+")) {
                return new ToolOutcome("ERROR: id 必须是数字(先用 action=list 查看可用文件)"
                        + ",不能按文件名猜测。当前收到: " + target, null, null, false);
            }
            return bounded(fileToolClient.preview(Long.parseLong(target)), null);
        }
        if ("manage_workspace".equals(name)) {
            if (agentWorkspaceService == null) {
                return new ToolOutcome("ERROR: 工作区能力未启用(服务未配置)", null, null, false);
            }
            String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
            if (!java.util.Set.of("list", "read", "write", "append", "delete").contains(action)) {
                return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 list / read / write / append / delete",
                        null, null, false);
            }
            try {
                JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
                String path = a.path("path").asText(null);
                return new ToolOutcome(switch (action) {
                    case "list" -> {
                        // dir 优先,其次 path(agent 可能把路径塞进 path)
                        String dirArg = a.path("dir").asText(null);
                        if (dirArg == null) {
                            dirArg = a.path("path").asText(null);
                        }
                        List<AgentWorkspaceService.FileEntry> entries =
                                agentWorkspaceService.listAny(dirArg);
                        if (entries.isEmpty()) {
                            yield "(空目录)";
                        }
                        StringBuilder sb = new StringBuilder("工作区文件(" + path + " 相对根目录):\n");
                        for (AgentWorkspaceService.FileEntry f : entries) {
                            sb.append(f.directory() ? "[目录] " : "").append(f.path())
                                    .append(f.directory() ? "" : " (" + f.size() + "B, " + f.modifiedAt() + ")")
                                    .append('\n');
                        }
                        yield sb.toString();
                    }
                    case "read" -> {
                        if (path == null || path.isBlank()) {
                            yield "ERROR: 缺少 path 参数。相对路径=工作区内(如 USER.md);绝对路径可读整机(如 D:/projects/x/README.md)";
                        }
                        yield agentWorkspaceService.readAny(path);
                    }
                    case "write" -> {
                        if (path == null || path.isBlank()) {
                            yield "ERROR: 缺少 path 参数(相对路径)";
                        }
                        String content = a.path("content").asText(null);
                        if (content == null) {
                            yield "ERROR: 缺少 content 参数(要写入的完整内容;如需保留原内容请先 read)";
                        }
                        int written = agentWorkspaceService.writeAny(path, content);
                        yield "已写入 " + path + "(" + written + " 字符)";
                    }
                    case "append" -> {
                        if (path == null || path.isBlank()) {
                            yield "ERROR: 缺少 path 参数(相对路径)";
                        }
                        String content = a.path("content").asText(null);
                        if (content == null || content.isBlank()) {
                            yield "ERROR: 缺少 content 参数(要追加的内容)";
                        }
                        int written = agentWorkspaceService.appendAny(path, content);
                        yield "已追加 " + written + " 字符到 " + path;
                    }
                    default -> {
                        if (path == null || path.isBlank()) {
                            yield "ERROR: 缺少 path 参数;删除不可恢复,请先向用户确认";
                        }
                        agentWorkspaceService.deleteAny(path);
                        yield "已删除 " + path;
                    }
                }, null, null, false);
            } catch (IllegalArgumentException e) {
                return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
            } catch (Exception e) {
                return new ToolOutcome("ERROR: 工作区操作失败: " + abbreviate(e.getMessage(), 200), null, null, false);
            }
        }
        if ("manage_skill".equals(name)) {
            if (agentSkillService == null) {
                return new ToolOutcome("ERROR: 技能能力未启用(服务未配置)", null, null, false);
            }
            String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
            if (!java.util.Set.of("list", "read", "create", "update", "remove").contains(action)) {
                return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 list / read / create / update / remove", null, null, false);
            }
            try {
                JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
                return new ToolOutcome(switch (action) {
                    case "list" -> {
                        List<AgentSkillService.SkillView> all = agentSkillService.list();
                        if (all.isEmpty()) {
                            yield "(暂无技能)";
                        }
                        StringBuilder sb = new StringBuilder("现有技能:\n");
                        for (AgentSkillService.SkillView s : all) {
                            sb.append("id=").append(s.id())
                                    .append(s.enabled() ? "" : " [已停用]")
                                    .append(" [").append(s.category()).append("] ")
                                    .append(s.name()).append(": ").append(s.description()).append('\n');
                        }
                        yield sb.toString();
                    }
                    case "read" -> {
                        String target = firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
                        AgentSkillService.SkillView skill = resolveSkill(target);
                        if (skill == null) {
                            yield "ERROR: 找不到技能「" + target + "」。可用技能:\n" + skillNameList();
                        }
                        yield "技能「" + skill.name() + "」完整指令:\n" + skill.instructions();
                    }
                    case "create" -> {
                        String sName = a.path("name").asText(null);
                        String instr = a.path("instructions").asText(null);
                        if (sName == null || sName.isBlank()) {
                            yield "ERROR: 缺少 name 参数(技能名称)";
                        }
                        if (instr == null || instr.isBlank()) {
                            yield "ERROR: 缺少 instructions 参数(技能正文)";
                        }
                        if (agentSkillService.getByName(sName.trim()) != null) {
                            yield "ERROR: 技能名「" + sName.trim() + "」已存在。如需修改用 action=update";
                        }
                        AgentSkillService.SkillView created = agentSkillService.create(
                                sName, a.path("description").asText(null), instr, a.path("category").asText(null));
                        yield "已创建技能(id=" + created.id() + "): " + created.name();
                    }
                    case "update" -> {
                        String target = firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
                        AgentSkillService.SkillView skill = resolveSkill(target);
                        if (skill == null) {
                            yield "ERROR: 找不到技能「" + target + "」。可用技能:\n" + skillNameList();
                        }
                        Boolean enabled = a.has("enabled") ? a.path("enabled").asBoolean() : null;
                        AgentSkillService.SkillView updated = agentSkillService.update(skill.id(),
                                a.has("name") ? a.path("name").asText(null) : null,
                                a.has("description") ? a.path("description").asText(null) : null,
                                a.has("instructions") ? a.path("instructions").asText(null) : null,
                                a.has("category") ? a.path("category").asText(null) : null,
                                enabled);
                        yield "已更新技能(id=" + updated.id() + ", " + (updated.enabled() ? "启用" : "停用") + "): " + updated.name();
                    }
                    default -> {
                        String target = firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
                        AgentSkillService.SkillView skill = resolveSkill(target);
                        if (skill == null) {
                            yield "ERROR: 找不到技能「" + target + "」。可用技能:\n" + skillNameList();
                        }
                        yield agentSkillService.delete(skill.id()) ? "已删除技能: " + skill.name() : "ERROR: 删除失败";
                    }
                }, null, null, false);
            } catch (IllegalArgumentException e) {
                return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return new ToolOutcome("ERROR: 技能名已存在,先 action=list 查看现有技能", null, null, false);
            } catch (Exception e) {
                return new ToolOutcome("ERROR: 技能操作失败: " + abbreviate(e.getMessage(), 200), null, null, false);
            }
        }
        // MCP 挂载工具兜底分发:名字带 mcp__ 前缀 → 路由到对应服务器执行;
        // 输出同样走 bounded 截断与脱敏
        if (mcpServerService != null && name.startsWith("mcp__")) {
            McpServerService.RawServer server = mcpServerService.serverForMountedTool(name);
            if (server == null) {
                return new ToolOutcome("ERROR: 找不到该工具对应的 MCP 服务器(可能已被禁用或删除): " + name,
                        null, null, false);
            }
            return bounded(mcpServerService.callTool(server.id(), McpServerService.rawToolName(name), args),
                    "MCP " + server.name() + " 执行完成");
        }
        return new ToolOutcome("ERROR: unknown tool " + name
                + ". 可用工具：execute_sql（只读 SQL,可选 datasource 参数）、execute_write_sql（写 SQL,需批准）、"
                + "read_service_logs（容器日志）、manage_container（容器启停,需批准）、"
                + "manage_datasource（数据源 list/create/test/schema/remove,create/remove 需批准）、"
                + "manage_service（纳管源 list/register/enable/disable/remove,register/remove 需批准）、"
                + "read_file（工作台文件 list/read,只读）、"
                + "manage_workspace（工作区文件 list/read/write/append/delete）、"
                + "manage_skill（技能 list/read/create/update/remove）"
                + (name.startsWith("mcp__") ? " 或已挂载的 MCP 工具(mcp__<server>__<tool>)" : ""), null, null, false);
    }

    /**
     * Guardrail: one read-only statement only. Rejections carry what was
     * refused, which rule, and a correct example.
     */
    static String guardSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "拒绝执行：缺少 sql 参数。该工具需要一条只读 SQL 语句，正确示例：{\"sql\": \"SELECT * FROM orders LIMIT 10\"}";
        }
        String normalized = sql.trim();
        if (!normalized.matches("(?is)^(SELECT|SHOW|EXPLAIN)\\b.*")) {
            return "拒绝执行「" + abbreviateStatic(normalized, 60) + "」：只允许单条 SELECT / SHOW / EXPLAIN 查询，"
                    + "不允许写入或修改数据。正确示例：{\"sql\": \"SELECT status, COUNT(*) FROM orders GROUP BY status\"}";
        }
        String withoutTrailing = normalized.replaceFirst(";\\s*$", "");
        if (withoutTrailing.contains(";")) {
            return "拒绝执行：一次只允许一条 SQL 语句（检测到多条）。请拆成多次调用，每次一条，"
                    + "示例：{\"sql\": \"SELECT 1\"}";
        }
        if (normalized.length() > 10000) {
            return "拒绝执行：SQL 超过 10000 字符上限（当前 " + normalized.length() + "）。"
                    + "请简化查询或改用视图/子查询减少字面量";
        }
        return null;
    }

    /** Guardrail for the service log tool: service must come from the registry. */
    static String guardService(String service) {
        if (service == null || service.isBlank()) {
            return "拒绝执行：缺少 service 参数。该工具需要容器名，可用值如：nora-postgres、nora-redis、nora-nacos";
        }
        return null;
    }

    /**
     * Bounds a raw tool result to the inline budget. Success keeps up to
     * {@value MAX_SUCCESS_CHARS} chars; anything larger is cut with a
     * truncation marker. Failures (ERROR:) get a head+tail excerpt instead.
     */
    private ToolOutcome bounded(String result, String summary) {
        if (result == null) {
            return new ToolOutcome("(empty)", summary, null, false);
        }
        String redacted = result.replaceAll("(?i)(api[_-]?key|password|token|secret)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[REDACTED]");
        boolean failure = redacted.startsWith("ERROR:");
        int budget = failure ? MAX_FAILURE_CHARS : MAX_SUCCESS_CHARS;
        if (redacted.length() <= budget) {
            if (failure) {
                return new ToolOutcome(redacted, summary, null, false);
            }
            return new ToolOutcome(redacted, summary != null ? summary : summarizeRows(redacted), null, false);
        }
        String cut = failure
                ? redacted.substring(0, FAILURE_HEAD_CHARS) + "\n…(中间省略)…\n"
                        + redacted.substring(redacted.length() - FAILURE_TAIL_CHARS)
                : redacted.substring(0, budget) + "\n…(结果已截断,请缩小查询范围,如加 LIMIT 或 WHERE)";
        return new ToolOutcome(cut, summary, null, true);
    }

    /** Derives a one-line row summary from a TSV render ("(3 rows, 12ms)" footer). */
    private String summarizeRows(String tsv) {
        int idx = tsv.lastIndexOf("(");
        if (idx >= 0 && tsv.endsWith(")")) {
            return tsv.substring(idx + 1, tsv.length() - 1);
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
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel), reasoningLevel);
        ObjectNode body = baseBody(true, llm);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
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
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel), reasoningLevel);
        ObjectNode body = baseBody(true, llm);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        // final round: the model must answer, not call more tools
        body.put("tool_choice", "none");
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
                orderedCalls.forEach(arr::add);
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
                    item.put("output", m.path("content").asText(""));
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
                orderedCalls.forEach(arr::add);
            }
            sseLog.info("upstream round done (responses): contentChars={} toolCalls={} usage={}",
                    content.length(), orderedCalls.size(),
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
     * - 支持 reasoning_effort 的家族(gpt-5/o/claude/gemini-*-high 等):none→minimal,
     *   其余档位原样透传;未设置时 gpt-5/o 默认 medium,claude thinking 与带档位后缀
     *   的模型不注入(由上游按模型自身默认决定,claude 不带该字段就没有推理内容)。
     * - qwen/glm:开关式字段,none 关、其余开。
     */
    private void applyReasoningRequest(ObjectNode body, ResolvedLlm llm) {
        // responses 协议走 reasoning:{effort} 映射(见 streamUpstreamResponses),同样需要注入档位
        if (!"openai".equalsIgnoreCase(llm.protocol()) && !"responses".equalsIgnoreCase(llm.protocol())) return;
        String model = llm.model() == null ? "" : llm.model().toLowerCase();
        String requested = llm.effectiveReasoningLevel();
        boolean hasRequested = requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested);
        boolean off = hasRequested && "none".equalsIgnoreCase(requested);
        boolean effortFamily = supportsReasoningEffort(model);
        boolean openAiDefaultFamily = model.contains("gpt-5") || model.matches("(?s).*\\bo[1-9].*");
        if (effortFamily) {
            if (off) {
                body.put("reasoning_effort", "minimal");
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
        return new ResolvedLlm(llm.baseUrl(), llm.apiKey(), llm.model(), llm.protocol(), reasoningLevel, llm.contextWindow());
    }

    /** Returns a copy of the resolved endpoint carrying the given reasoning level. */

    /** Package-visible for tests; never returned outside the service. */
    record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,
                       /** 生效思考等级(已合并请求级与设置页默认);null = auto */
                       String effectiveReasoningLevel,
                       /** 模型上下文窗口(tokens);null = 未配置 */
                       Long contextWindow) {}

    private ArrayNode messagesArray(List<WireMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (WireMessage message : messages) {
            array.add(message.node());
        }
        return array;
    }

    /** OpenAI tools array: guarded SQL + service log reading. */
    private ArrayNode toolsSpec() {
        ArrayNode tools = objectMapper.createArrayNode();

        ObjectNode sqlTool = objectMapper.createObjectNode();
        sqlTool.put("type", "function");
        ObjectNode sqlFn = sqlTool.putObject("function");
        sqlFn.put("name", "execute_sql");
        sqlFn.put("description", "在已连接的数据库上执行只读 SQL 查询并返回真实结果。需要数据库统计、表数据时必须使用此工具。"
                + "限制：仅允许单条 SELECT/SHOW/EXPLAIN 语句；一次调用一条语句；不允许 INSERT/UPDATE/DELETE/DDL；"
                + "结果最多返回 50 行,超长会被截断——请先加 LIMIT 探查再逐步细化。示例:{\"sql\": \"SELECT status, COUNT(*) FROM orders GROUP BY status\"}");
        ObjectNode sqlParams = sqlFn.putObject("parameters");
        sqlParams.put("type", "object");
        sqlParams.put("additionalProperties", false);
        ObjectNode sqlProps = sqlParams.putObject("properties");
        ObjectNode sqlProp = sqlProps.putObject("sql");
        sqlProp.put("type", "string");
        sqlProp.put("description", "要执行的只读 SQL 语句,必须是单条完整的 SELECT/SHOW/EXPLAIN,不要带分号以外的多条语句");
        ObjectNode sqlDsProp = sqlProps.putObject("datasource");
        sqlDsProp.put("type", "string");
        sqlDsProp.put("description", "可选:目标数据源的连接名或 id(以 manage_datasource action=list 返回的 name/id 为准,"
                + "不要猜测数据库名);单连接时省略此参数");
        ObjectNode sqlDescProp = sqlProps.putObject("description");
        sqlDescProp.put("type", "string");
        sqlDescProp.put("description", "一句话描述这次调用要做什么,将作为执行时间线的标题展示给用户(5-12 个字,祈使句)。"
                + "正例:「统计各状态订单数」「查最近 50 行日志」;反例:不要出现「复杂」「风险」等主观词,不要复述完整 SQL");
        ArrayNode sqlRequired = sqlParams.putArray("required");
        sqlRequired.add("sql");
        tools.add(sqlTool);

        ObjectNode logTool = objectMapper.createObjectNode();
        logTool.put("type", "function");
        ObjectNode logFn = logTool.putObject("function");
        logFn.put("name", "read_service_logs");
        logFn.put("description", "读取本地 Docker 容器服务的最近日志,用于诊断服务异常/报错。"
                + "限制:service 必须是已注册容器名;最多返回最近 100 行;DEBUG 级别日志会被过滤。"
                + "不确定有哪些服务时,不要凭空猜测容器名。示例:{\"service\": \"nora-redis\", \"limit\": 50}");
        ObjectNode logParams = logFn.putObject("parameters");
        logParams.put("type", "object");
        logParams.put("additionalProperties", false);
        ObjectNode logProps = logParams.putObject("properties");
        ObjectNode serviceProp = logProps.putObject("service");
        serviceProp.put("type", "string");
        serviceProp.put("description", "容器名,如 nora-postgres / nora-redis / nora-nacos");
        ObjectNode limitProp = logProps.putObject("limit");
        limitProp.put("type", "integer");
        limitProp.put("description", "返回的最近日志行数,默认 50,最大 100");
        ObjectNode logDescProp = logProps.putObject("description");
        logDescProp.put("type", "string");
        logDescProp.put("description", "一句话描述这次调用要做什么,将作为执行时间线的标题展示给用户(5-12 个字,祈使句)。"
                + "正例:「诊断 Redis 启动日志」「排查 Postgres 报错」;反例:不要用「查看日志」这类与工具名重复的泛化描述");
        ArrayNode logRequired = logParams.putArray("required");
        logRequired.add("service");
        tools.add(logTool);

        // 写 SQL:仅在用户批准后执行(ASK/ASSIST 档)。描述必须让模型明白这会真的改数据
        ObjectNode writeTool = objectMapper.createObjectNode();
        writeTool.put("type", "function");
        ObjectNode writeFn = writeTool.putObject("function");
        writeFn.put("name", "execute_write_sql");
        writeFn.put("description", "在已连接的数据库上执行一条写语句(INSERT/UPDATE/DELETE/DDL),会真实修改数据。"
                + "仅当用户明确要求修改/删除/新增数据时使用;只读查询必须用 execute_sql。"
                + "限制:一次只允许一条写语句;执行前用户会收到审批请求,未批准则不会执行。"
                + "示例:{\"sql\": \"UPDATE orders SET status='paid' WHERE id=42\"}");
        ObjectNode writeParams = writeFn.putObject("parameters");
        writeParams.put("type", "object");
        writeParams.put("additionalProperties", false);
        ObjectNode writeProps = writeParams.putObject("properties");
        ObjectNode writeSqlProp = writeProps.putObject("sql");
        writeSqlProp.put("type", "string");
        writeSqlProp.put("description", "要执行的单条写 SQL(INSERT/UPDATE/DELETE/DDL),不要带 WHERE 以外的子查询副作用");
        ObjectNode writeDsProp = writeProps.putObject("datasource");
        writeDsProp.put("type", "string");
        writeDsProp.put("description", "可选:目标数据源的名称或 id(不填=默认连接)");
        ObjectNode writeDescProp = writeProps.putObject("description");
        writeDescProp.put("type", "string");
        writeDescProp.put("description", "一句话描述这次写操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)。"
                + "正例:「更新订单 42 状态」;反例:不要出现「危险」「风险」等主观词");
        ArrayNode writeRequired = writeParams.putArray("required");
        writeRequired.add("sql");
        tools.add(writeTool);

        // 容器控制:仅在用户批准后执行(ASK/ASSIST 档)
        ObjectNode containerTool = objectMapper.createObjectNode();
        containerTool.put("type", "function");
        ObjectNode containerFn = containerTool.putObject("function");
        containerFn.put("name", "manage_container");
        containerFn.put("description", "启动/停止/重启本地 Docker 容器服务。仅在诊断确认服务异常且需要重启才能恢复时使用;"
                + "只看日志用 read_service_logs。限制:service 必须是已注册容器名;action 只允许 start/stop/restart;"
                + "执行前用户会收到审批请求,未批准则不会执行。示例:{\"service\": \"nora-redis\", \"action\": \"restart\"}");
        ObjectNode containerParams = containerFn.putObject("parameters");
        containerParams.put("type", "object");
        containerParams.put("additionalProperties", false);
        ObjectNode containerProps = containerParams.putObject("properties");
        ObjectNode containerServiceProp = containerProps.putObject("service");
        containerServiceProp.put("type", "string");
        containerServiceProp.put("description", "容器名,如 nora-postgres / nora-redis / nora-nacos");
        ObjectNode containerActionProp = containerProps.putObject("action");
        containerActionProp.put("type", "string");
        containerActionProp.put("description", "要执行的动作:start / stop / restart");
        ObjectNode containerDescProp = containerProps.putObject("description");
        containerDescProp.put("type", "string");
        containerDescProp.put("description", "一句话描述这次容器操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)。"
                + "正例:「重启 Redis 恢复连接」;反例:不要用「操作容器」这类泛化描述");
        ArrayNode containerRequired = containerParams.putArray("required");
        containerRequired.add("service");
        containerRequired.add("action");
        tools.add(containerTool);

        // 数据源管理:list/schema 只读自动;create/test ASSIST+FULL 需批准(见 RiskClassifier);
        // remove CRITICAL——任何档位都要用户确认(级联删历史,不可逆)
        ObjectNode dsTool = objectMapper.createObjectNode();
        dsTool.put("type", "function");
        ObjectNode dsFn = dsTool.putObject("function");
        dsFn.put("name", "manage_datasource");
        dsFn.put("description", "管理数据库连接:list=列出全部连接;create=新增连接(用户提供 host/port/账号密码,"
                + "创建后自动测试连通性);test=测试某连接;schema=查看某连接的表结构(免掉探索性 SQL);"
                + "remove=删除连接(其查询历史一并删除)。用户明确要求添加/删除数据库时使用;"
                + "create 和 remove 在执行前会收到审批请求。示例:{\"action\": \"list\"}");
        ObjectNode dsParams = dsFn.putObject("parameters");
        dsParams.put("type", "object");
        dsParams.put("additionalProperties", false);
        ObjectNode dsProps = dsParams.putObject("properties");
        ObjectNode dsActionProp = dsProps.putObject("action");
        dsActionProp.put("type", "string");
        dsActionProp.put("description", "list / create / test / schema / remove");
        ObjectNode dsNameProp = dsProps.putObject("name");
        dsNameProp.put("type", "string");
        dsNameProp.put("description", "create 时:连接显示名;其他 action 时省略(用 target 定位)");
        ObjectNode dsTargetProp = dsProps.putObject("target");
        dsTargetProp.put("type", "string");
        dsTargetProp.put("description", "test/schema/remove 时:目标数据源的名称或 id");
        ObjectNode dsEngineProp = dsProps.putObject("engine");
        dsEngineProp.put("type", "string");
        dsEngineProp.put("description", "create 时:postgresql 或 mysql");
        ObjectNode dsHostProp = dsProps.putObject("host");
        dsHostProp.put("type", "string");
        dsHostProp.put("description", "create 时:数据库主机名或 IP");
        ObjectNode dsPortProp = dsProps.putObject("port");
        dsPortProp.put("type", "integer");
        dsPortProp.put("description", "create 时:端口(如 postgresql 5432 / mysql 3306)");
        ObjectNode dsDbProp = dsProps.putObject("database");
        dsDbProp.put("type", "string");
        dsDbProp.put("description", "create 时:数据库名");
        ObjectNode dsUserProp = dsProps.putObject("username");
        dsUserProp.put("type", "string");
        dsUserProp.put("description", "create 时:用户名(可选)");
        ObjectNode dsPwdProp = dsProps.putObject("password");
        dsPwdProp.put("type", "string");
        dsPwdProp.put("description", "create 时:密码(可选;仅存入数据源服务,不会出现在对话与日志)");
        ObjectNode dsDescProp = dsProps.putObject("description");
        dsDescProp.put("type", "string");
        dsDescProp.put("description", "一句话描述这次操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)");
        ArrayNode dsRequired = dsParams.putArray("required");
        dsRequired.add("action");
        tools.add(dsTool);

        // 纳管源管理:list/enable/disable ASSIST 需批准;register/remove CRITICAL——
        // 任何档位都要用户确认(PROC 源的 command 是宿主机命令,注册等于纳入监控+守护)
        ObjectNode svcTool = objectMapper.createObjectNode();
        svcTool.put("type", "function");
        ObjectNode svcFn = svcTool.putObject("function");
        svcFn.put("name", "manage_service");
        svcFn.put("description", "管理环境纳管源(日志/监控注册表):list=列出全部;register=注册新源"
                + "(FILE=日志文件,DOCKER=容器名,PROC=宿主机进程及启动命令);enable/disable=暂停或恢复监控;"
                + "remove=删除注册(不动容器/文件)。注册 PROC 源意味着系统将跟踪其命令并采集日志,"
                + "register 和 remove 执行前会收到审批请求。示例:{\"action\": \"register\", \"kind\": \"PROC\","
                + " \"name\": \"backup-job\", \"command\": \"/opt/scripts/backup.sh\", \"workDir\": \"/opt/scripts\"}");
        ObjectNode svcParams = svcFn.putObject("parameters");
        svcParams.put("type", "object");
        svcParams.put("additionalProperties", false);
        ObjectNode svcProps = svcParams.putObject("properties");
        ObjectNode svcActionProp = svcProps.putObject("action");
        svcActionProp.put("type", "string");
        svcActionProp.put("description", "list / register / enable / disable / remove");
        ObjectNode svcKindProp = svcProps.putObject("kind");
        svcKindProp.put("type", "string");
        svcKindProp.put("description", "register 时:FILE / DOCKER / PROC");
        ObjectNode svcNameProp = svcProps.putObject("name");
        svcNameProp.put("type", "string");
        svcNameProp.put("description", "register 时:纳管源唯一显示名;其他 action 时省略(用 target 定位)");
        ObjectNode svcFileProp = svcProps.putObject("fileLogPath");
        svcFileProp.put("type", "string");
        svcFileProp.put("description", "kind=FILE 时:日志文件绝对路径");
        ObjectNode svcContainerProp = svcProps.putObject("containerName");
        svcContainerProp.put("type", "string");
        svcContainerProp.put("description", "kind=DOCKER 时:容器名");
        ObjectNode svcCmdProp = svcProps.putObject("command");
        svcCmdProp.put("type", "string");
        svcCmdProp.put("description", "kind=PROC 时:启动命令(绝对路径或可执行文件)");
        ObjectNode svcWorkDirProp = svcProps.putObject("workDir");
        svcWorkDirProp.put("type", "string");
        svcWorkDirProp.put("description", "kind=PROC 时:工作目录(可选)");
        ObjectNode svcTargetProp = svcProps.putObject("target");
        svcTargetProp.put("type", "string");
        svcTargetProp.put("description", "enable/disable/remove 时:目标纳管源的名称或 id");
        ObjectNode svcDescProp = svcProps.putObject("description");
        svcDescProp.put("type", "string");
        svcDescProp.put("description", "一句话描述这次操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)");
        ArrayNode svcRequired = svcParams.putArray("required");
        svcRequired.add("action");
        tools.add(svcTool);

        // 文件读取:纯只读(LOW),无审批;文件名猜不准,先 list 再按 id 读
        ObjectNode fileTool = objectMapper.createObjectNode();
        fileTool.put("type", "function");
        ObjectNode fileFn = fileTool.putObject("function");
        fileFn.put("name", "read_file");
        fileFn.put("description", "读取工作台已上传文件的内容(list 列出全部文件;带 id 读取某个文件的提取文本,"
                + "支持文档/PDF/代码等)。用户问\"我的文件里/上传的文档里\"这类问题时使用——"
                + "注意 RAG 检索只能召回片段,通读全文用此工具。文件名不能猜,必须先 list 拿到 id。"
                + "示例:{\"action\": \"list\"} 或 {\"id\": \"3\"}");
        ObjectNode fileParams = fileFn.putObject("parameters");
        fileParams.put("type", "object");
        fileParams.put("additionalProperties", false);
        ObjectNode fileProps = fileParams.putObject("properties");
        ObjectNode fileActionProp = fileProps.putObject("action");
        fileActionProp.put("type", "string");
        fileActionProp.put("description", "list(列文件)或 read(读内容);省略时:有 id 即 read,无 id 即 list");
        ObjectNode fileIdProp = fileProps.putObject("id");
        fileIdProp.put("type", "string");
        fileIdProp.put("description", "read 时:文件 id(list 结果里的数字 id,非文件名)");
        ObjectNode fileDescProp = fileProps.putObject("description");
        fileDescProp.put("type", "string");
        fileDescProp.put("description", "一句话描述这次调用要做什么(5-12 个字,祈使句)");
        ArrayNode fileRequired = fileParams.putArray("required");
        tools.add(fileTool);

        // 工作区文件操作(设计对齐 OpenClaw workspace):agent 的记忆载体是文件,
        // 工作区就是它的家——USER.md/MEMORY.md/memory 日记由它自己维护。
        // 全部动作锁定在工作区内(路径校验),LOW 风险,任何档位自动执行。
        if (agentWorkspaceService != null) {
        ObjectNode wsTool = objectMapper.createObjectNode();
        wsTool.put("type", "function");
        ObjectNode wsFn = wsTool.putObject("function");
        wsFn.put("name", "manage_workspace");
        wsFn.put("description", "文件系统读写(工作区是你的家目录,也是你的长期记忆)。"
                + "list 列目录;read 读文件;write 覆盖写入;append 追加;delete 删除。"
                + "**相对路径=工作区内**(如 USER.md、memory/2026-09-10.md);"
                + "**绝对路径=整机任意位置**(如 D:/projects/app/src/main.ts、C:/Users/xxx/notes.md),"
                + "可帮用户查看/整理项目文件——写/删工作区外的文件前用户会被要求确认。"
                + "记忆维护约定:稳定偏好→USER.md,耐久事实/决定→MEMORY.md(保持精简,每轮自动注入),"
                + "日常观察/进度→memory/YYYY-MM-DD.md(按需读取)。用户说「记住…」时必须落盘。"
                + "示例:{\"action\": \"read\", \"path\": \"D:/projects/myapp/package.json\"}");
        ObjectNode wsParams = wsFn.putObject("parameters");
        wsParams.put("type", "object");
        wsParams.put("additionalProperties", false);
        ObjectNode wsProps = wsParams.putObject("properties");
        ObjectNode wsActionProp = wsProps.putObject("action");
        wsActionProp.put("type", "string");
        wsActionProp.put("description", "list / read / write / append / delete");
        ObjectNode wsPathProp = wsProps.putObject("path");
        wsPathProp.put("type", "string");
        wsPathProp.put("description", "read/write/append/delete 时:相对路径=工作区内(如 USER.md);绝对路径=整机(如 D:/projects/x/README.md;写/删前会被要求确认)");
        ObjectNode wsDirProp = wsProps.putObject("dir");
        wsDirProp.put("type", "string");
        wsDirProp.put("description", "list 时:目录(相对=工作区内;绝对=整机;省略=工作区根目录)");
        ObjectNode wsContentProp = wsProps.putObject("content");
        wsContentProp.put("type", "string");
        wsContentProp.put("description", "write/append 时:文件内容(write 会覆盖整文件,先 read 再写)");
        ObjectNode wsDescProp = wsProps.putObject("description");
        wsDescProp.put("type", "string");
        wsDescProp.put("description", "一句话描述这次操作的目的(5-12 个字,祈使句)");
        ArrayNode wsRequired = wsParams.putArray("required");
        wsRequired.add("action");
        tools.add(wsTool);
        }

        // 技能管理:目录已在系统提示注入,read 拉全文遵循;create/update 让 agent
        // 能把用户教的方法沉淀为可复用技能(闭环自管)。
        if (agentSkillService != null) {
        ObjectNode skillTool = objectMapper.createObjectNode();
        skillTool.put("type", "function");
        ObjectNode skillFn = skillTool.putObject("function");
        skillFn.put("name", "manage_skill");
        skillFn.put("description", "管理用户的技能库(可复用的任务指令)。系统提示里列出了启用技能目录;"
                + "任务与某技能相关时用 action=read 读它的完整指令并严格遵循;"
                + "用户教你一套方法并希望以后沿用(\"把刚才的流程存成技能\")时用 action=create 沉淀;"
                + "list 查看全部技能;update 修改;remove 删除。"
                + "示例:{\"action\": \"read\", \"target\": \"周报生成\"}");
        ObjectNode skillParams = skillFn.putObject("parameters");
        skillParams.put("type", "object");
        skillParams.put("additionalProperties", false);
        ObjectNode skillProps = skillParams.putObject("properties");
        ObjectNode skillActionProp = skillProps.putObject("action");
        skillActionProp.put("type", "string");
        skillActionProp.put("description", "list / read / create / update / remove");
        ObjectNode skillTargetProp = skillProps.putObject("target");
        skillTargetProp.put("type", "string");
        skillTargetProp.put("description", "read/update/remove 时:技能名称或数字 id");
        ObjectNode skillNameProp = skillProps.putObject("name");
        skillNameProp.put("type", "string");
        skillNameProp.put("description", "create/update 时:技能名称");
        ObjectNode skillDescProp = skillProps.putObject("description");
        skillDescProp.put("type", "string");
        skillDescProp.put("description", "create/update 时:技能的一句话说明(何时该用这个技能)");
        ObjectNode skillInstrProp = skillProps.putObject("instructions");
        skillInstrProp.put("type", "string");
        skillInstrProp.put("description", "create/update 时:技能正文(Markdown,完整可执行的指令步骤)");
        ObjectNode skillCategoryProp = skillProps.putObject("category");
        skillCategoryProp.put("type", "string");
        skillCategoryProp.put("description", "create/update 时:分类(可选,默认「自定义」)");
        ObjectNode skillEnabledProp = skillProps.putObject("enabled");
        skillEnabledProp.put("type", "boolean");
        skillEnabledProp.put("description", "update 时:启用(true)/停用(false)该技能");
        ArrayNode skillRequired = skillParams.putArray("required");
        skillRequired.add("action");
        tools.add(skillTool);
        }

        // MCP 挂载工具:已启用且完成过 refresh(有 tools_cache)的远程服务器的
        // 工具,命名 mcp__<server>__<tool>;schema/描述来自远端 snapshot。
        // mcpServerService 为 null = 测试便捷构造器,跳过挂载
        if (mcpServerService != null) {
            for (McpServerService.MountedTool mounted : mcpServerService.mountedTools()) {
                ObjectNode mTool = objectMapper.createObjectNode();
                mTool.put("type", "function");
                ObjectNode mFn = mTool.putObject("function");
                mFn.put("name", mounted.mountedName());
                String desc = mounted.description() == null || mounted.description().isBlank()
                        ? "MCP 工具(" + mounted.serverName() + " 提供)" : mounted.description();
                mFn.put("description", desc);
                ObjectNode mParams = mFn.putObject("parameters");
                if (mounted.inputSchema() != null && mounted.inputSchema().isObject()) {
                    mParams.setAll((ObjectNode) mounted.inputSchema());
                } else {
                    mParams.put("type", "object");
                    mParams.putObject("properties");
                }
                tools.add(mTool);
            }
        }

        return tools;
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
                                            ContextBudget budget) {
        List<WireMessage> messages = new ArrayList<>();
        String systemPrompt = systemPromptWith(citations);
        messages.add(WireMessage.system(objectMapper, systemPrompt));
        String reflectionBlock = reflections == null || reflections.isEmpty() ? null
                : "此前类似任务的失败反思（仅作参考）：\n- " + String.join("\n- ", reflections);
        if (reflectionBlock != null) {
            messages.add(WireMessage.system(objectMapper, reflectionBlock));
        }

        int fixedCost = ContextBudget.estimateTokens(systemPrompt)
                + (reflectionBlock == null ? 0 : ContextBudget.estimateTokens(reflectionBlock))
                + ContextBudget.estimateTokens(userMessage)
                + PER_REQUEST_OVERHEAD_TOKENS;
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
            int cost = ContextBudget.estimateTokens(prev.content()) + 8;
            if (used + cost > historyBudget && from < historyEnd) break; // 至少保留 1 条
            used += cost;
            from--;
            if (historyEnd - from >= 40) break; // 条数硬上限,防御超长单条
        }
        for (int i = from; i < historyEnd; i++) {
            ChatStoreService.StoredMessage msg = history.get(i);
            if ("user".equals(msg.role())) {
                messages.add(WireMessage.user(objectMapper, msg.content()));
            } else if ("assistant".equals(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                messages.add(WireMessage.assistant(objectMapper, msg.content()));
            }
        }
        messages.add(WireMessage.user(objectMapper, userMessage));
        return messages;
    }

    /** 轮内微压缩永不触碰的尾部消息数(最近一轮工具往返 + 余量)。 */
    private static final int COMPACTION_TAIL_KEEP = 6;

    /**
     * 每次请求的固定 overhead token 估算(tools spec JSON schema ~1.5k +
     * 协议封装/系统字段;实测校准 2026-09-08:消息体估算与上游真实
     * inputTokens 比值 6.7~10.4,overhead 主导,必须计入预算)。
     */
    static final int PER_REQUEST_OVERHEAD_TOKENS = 1_800;

    /**
     * 轮内微压缩(microcompact,语义对齐 Claude Code):预估 prompt 超过
     * 触发线时,把最旧的工具结果就地改写为头尾摘录。只改 content,role/
     * tool_call_id 不动(OpenAI tool_call 配对校验不破),最近
     * {@link #COMPACTION_TAIL_KEEP} 条消息完整保留;历史轮的失败也只留要点。
     * force=true(上游已报超限)时压缩目标降为恢复线(窗口 60%)。
     */
    private int compactForRound(List<WireMessage> messages, ContextBudget budget, boolean force,
                                long overheadTokens) {
        long total = 0;
        for (WireMessage m : messages) {
            total += messageTokens(m);
        }
        total += overheadTokens; // tools spec + 协议封装,占请求大头
        long limit = force
                ? (long) (budget.window() * ContextBudget.RECOVERY_RATIO)
                : budget.triggerTokens() - budget.outputReserve();
        if (total <= limit) return 0;
        int compactedCount = 0;
        long freed = 0;
        int lastCompactable = messages.size() - COMPACTION_TAIL_KEEP;
        for (int i = 1; i < lastCompactable && total > limit; i++) { // i=0 system 不动
            WireMessage m = messages.get(i);
            if (!"tool".equals(m.node().path("role").asText())) continue;
            String content = m.node().path("content").asText("");
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
        int tokens = ContextBudget.estimateTokens(n.path("content").asText("")) + 8;
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
     * 估算校准日志:每轮结束把服务端 prompt 估算与上游真实 inputTokens
     * 对齐输出(真实值含 tools spec/协议封装 overhead,估算只含消息体)。
     * 比值持续偏离预期时调整 ContextBudget 估算系数——观测驱动的闭环。
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
     */
    private String systemPromptWith(List<CitationDto> citations) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        // 全局记忆:跨会话事实/偏好,约束每轮回答
        if (agentWorkspaceService != null) {
            try {
                String bootstrap = agentWorkspaceService.bootstrapPrompt();
                if (bootstrap != null && !bootstrap.isBlank()) {
                    sb.append('\n').append(bootstrap);
                }
            } catch (Exception e) {
                log.warn("workspace inject failed (ignored): {}", e.getMessage());
            }
        }
        // 技能目录:列出启用技能的名称/描述,正文由 manage_skill action=read 按需拉取
        if (agentSkillService != null) {
            try {
                String catalog = agentSkillService.catalogBlock();
                if (catalog != null && !catalog.isBlank()) {
                    sb.append('\n').append(catalog);
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
        return sb.toString();
    }

    private static String abbreviateForSse(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…(" + text.length() + " chars)";
    }

    /** 技能定位:target 是数字 → 按 id,否则按名称(不区分大小写)。 */
    private AgentSkillService.SkillView resolveSkill(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String trimmed = target.trim();
        if (trimmed.matches("\\d+")) {
            AgentSkillService.SkillView byId = agentSkillService.get(Long.parseLong(trimmed));
            if (byId != null) {
                return byId;
            }
        }
        return agentSkillService.getByName(trimmed);
    }

    private String skillNameList() {
        List<AgentSkillService.SkillView> all = agentSkillService.list();
        if (all.isEmpty()) {
            return "(暂无技能)";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentSkillService.SkillView s : all) {
            sb.append("- ").append(s.name()).append(s.enabled() ? "" : " [已停用]").append('\n');
        }
        return sb.toString();
    }

    /** 数字 id 解析:非法返回 -1(调用方给引导文案)。 */
    private static long parseNumericId(String raw) {
        if (raw == null || !raw.trim().matches("\\d+")) {
            return -1;
        }
        return Long.parseLong(raw.trim());
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String abbreviate(String text, int max) {        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String abbreviateStatic(String text, int max) {
        return abbreviate(text, max);
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
                // 中转站透传的 "Upstream error: N" 无信息量,补一句状态码语义
                if (message.matches("(?i)upstream (error|unavailable).*")) {
                    return message + "（上游模型服务暂不可用,稍后重试）";
                }
                return abbreviate(message, 160);
            }
        }
        return abbreviate(trimmed, 160);
    }

    /** Callbacks for the SSE events of one chat turn. */
    public interface ChatEventConsumer {

        void step(ChatStepDto step);

        void delta(String token);

        default void reasoningDelta(Integer roundIndex, String token) {
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
