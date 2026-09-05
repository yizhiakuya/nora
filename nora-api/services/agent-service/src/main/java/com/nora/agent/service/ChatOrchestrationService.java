package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.config.LlmProperties;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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

    private static final String SYSTEM_PROMPT = """
            你是 Nora 个人工作台的助手。回答必须：
            1. 优先使用检索到的知识库内容
            2. 引用来源时使用 [[docName]] 标记
            3. 涉及数据库统计/查询时,先用 execute_sql 工具查询真实数据再回答
            4. 诊断服务异常/报错时,先用 read_service_logs 工具读取相关容器日志,基于真实日志分析原因并给出修复建议
            5. 工具返回错误时如实说明,不要编造数据
            6. 最终回答使用自然的 Markdown：段落紧凑、结论前置；只在数据需要逐项对比时使用表格；不要复述工具参数或执行过程
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
    private final ModelProviderService modelProviderService;
    private final int maxToolRounds;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    ModelProviderService modelProviderService,
                                    @org.springframework.beans.factory.annotation.Value("${nora.agent.max-tool-rounds:10}") int maxToolRounds) {
        this.llmProperties = llmProperties;
        this.ragRetrievalClient = ragRetrievalClient;
        this.sqlToolClient = sqlToolClient;
        this.serviceLogClient = serviceLogClient;
        this.objectMapper = objectMapper;
        this.modelProviderService = modelProviderService;
        this.maxToolRounds = Math.max(1, maxToolRounds);
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
                DEFAULT_MAX_TOOL_ROUNDS);
    }

    /** Test entry: explicit max tool rounds, no provider store. */
    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper,
                                    int maxToolRounds) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogClient, objectMapper, null, maxToolRounds);
    }

    private ChatOrchestrationService(LlmProperties llmProperties,
                                     RagRetrievalClient ragRetrievalClient,
                                     SqlToolClient sqlToolClient,
                                     ServiceLogClient serviceLogService,
                                     ObjectMapper objectMapper,
                                     ModelProviderService modelProviderService,
                                     boolean ignored) {
        this(llmProperties, ragRetrievalClient, sqlToolClient, serviceLogService, objectMapper, modelProviderService,
                DEFAULT_MAX_TOOL_ROUNDS);
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
            return CompletableFuture.completedFuture(new ChatTurn(message, List.of(), null));
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
        List<WireMessage> messages = buildMessages(userMessage, history, citations, reflections);
        // 先解析一次拿到合并后的思考等级(请求级 > 设置页该模型默认),工具轮与最终回答共用
        ResolvedLlm resolved = resolveLlm(requestedModel, requestedReasoningLevel);
        if (resolved == null) {
            // configured() 已校验过,这里防御性兜底
            throw new IllegalStateException("no LLM provider resolved for model: " + requestedModel);
        }
        final String reasoningLevel = resolved.effectiveReasoningLevel();
        String toolOutcome = null;
        final int[] roundsUsed = {0};
        TokenUsage totalUsage = null;
        // loop breaker state: (toolName + normalized args) → consecutive repeat count
        Map<String, Integer> callFingerprints = new HashMap<>();
        try {
            for (int round = 0; round < maxToolRounds; round++) {
                roundsUsed[0] = round + 1;
                StreamTurnResult result = streamTurn(messages, requestedModel, reasoningLevel,
                        round + 1, eventConsumer);
                if (result.usage() != null) {
                    totalUsage = totalUsage == null ? result.usage() : totalUsage.add(result.usage());
                }
                if (result.failed) {
                    eventConsumer.step(new ChatStepDto("s-error", "think", "模型返回空响应",
                            result.errorMessage != null ? result.errorMessage : "上游未返回内容,请重试",
                            null, "failed", null, null, null, round + 1));
                    break;
                }
                if (result.toolCalls.isEmpty()) {
                    // 回答(与推理)已随流逐 token 转发完毕
                    return CompletableFuture.completedFuture(new ChatTurn(result.content, citations, totalUsage));
                }
                messages.add(new WireMessage(result.assistantMessage));
                for (JsonNode call : result.toolCalls) {
                    String callId = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String args = call.path("function").path("arguments").asText("{}");
                    String toolStepId = "s-call-" + callFingerprints.size() + "-" + callId;
                    emitToolStep(toolStepId, name, args, callFingerprints, messages, callId,
                            round + 1, eventConsumer);
                    String lastResult = lastToolResult(messages);
                    if (lastResult != null) {
                        toolOutcome = lastResult;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("tool loop failed, falling back to plain answer: {}", e.getMessage());
        }

        // Step 3: forced-answer streaming round (with tool outcome already in the messages)
        long answerStart = System.currentTimeMillis();
        final String fallbackOutcome = toolOutcome;

        final int reasoningRound = roundsUsed[0] + 1;
        StreamTurnResult finalResult = streamFinalAnswer(messages, requestedModel, reasoningLevel,
                reasoningRound, eventConsumer);
        if (!finalResult.failed) {
            String answerText = finalResult.content;
            if (finalResult.usage() != null) {
                totalUsage = totalUsage == null ? finalResult.usage() : totalUsage.add(finalResult.usage());
            }
            if (!answerText.isBlank()) {
                return CompletableFuture.completedFuture(new ChatTurn(answerText, citations, totalUsage));
            }
        }
        // 流式最终回答失败:回落到最后一次工具结果作为回答,而不是死流
        String fallback = fallbackOutcome != null && !fallbackOutcome.isBlank()
                ? "查询结果：\n```\n" + abbreviate(fallbackOutcome, MAX_FAILURE_CHARS) + "\n```"
                : "";
        if (!fallback.isBlank()) {
            eventConsumer.delta(fallback);
            return CompletableFuture.completedFuture(new ChatTurn(fallback, citations, totalUsage));
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
     */
    private void emitToolStep(String toolStepId, String name, String args,
                              Map<String, Integer> fingerprints,
                              List<WireMessage> messages, String callId,
                              int roundIndex,
                              ChatEventConsumer eventConsumer) {
        ParsedArgs parsed = parseArgs(args);
        ChatStepDto.StepInput input = parsed.input();
        // Claude Code pattern: the model fills the display title via the
        // description arg (imperative, no subjective words); fall back to
        // the tool name when it omits one
        String title = parsed.description() != null && !parsed.description().isBlank()
                ? parsed.description() : defaultTitle(name);
        long toolStart = System.currentTimeMillis();
        eventConsumer.step(new ChatStepDto(toolStepId, "tool", title,
                null, null, "running", name, input, null, roundIndex));

        // loop breaker: same (tool, normalized args) repeated too often
        String fingerprint = name + "|" + normalizeArgs(name, input);
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
    record ParsedArgs(ChatStepDto.StepInput input, String description) {
    }

    /** Parses tool arguments into the typed input shown by the frontend + the display title. */
    private ParsedArgs parseArgs(String argsJson) {
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            String description = node.path("description").asText(null);
            if (description != null && description.length() > 120) {
                description = description.substring(0, 120);
            }
            if (node.has("sql")) {
                return new ParsedArgs(new ChatStepDto.StepInput(node.path("sql").asText(null), null, null), description);
            }
            if (node.has("service") || node.has("limit")) {
                Integer limit = node.has("limit") && node.get("limit").isNumber()
                        ? node.get("limit").asInt() : null;
                return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), limit), description);
            }
            return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), description);
        } catch (Exception e) {
            return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), null);
        }
    }

    /** Human title when the model omits the description arg. */
    private String defaultTitle(String name) {
        return switch (name) {
            case "execute_sql" -> "查询数据库";
            case "read_service_logs" -> "读取服务日志";
            default -> name;
        };
    }

    /** Stable string for loop detection: the meaningful part of the args. */
    private String normalizeArgs(String name, ChatStepDto.StepInput input) {
        if ("execute_sql".equals(name)) return input.sql() == null ? "" : input.sql().trim().toLowerCase();
        if ("read_service_logs".equals(name)) return (input.service() == null ? "" : input.service()) + "#" + input.limit();
        return "";
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
        if (llmProperties.configured()) {
            return new ResolvedLlm(llmProperties.baseUrl(), llmProperties.apiKey(), llmProperties.model(), "openai",
                    null);
        }
        if (modelProviderService == null) return null;
        ModelProviderService.ActiveProvider provider = modelProviderService.activeProvider(requestedModel);
        if (provider == null || provider.endpoint() == null || provider.endpoint().isBlank()) return null;
        String model = provider.models() == null || provider.models().isEmpty()
                ? LlmProperties.DEFAULT_MODEL : provider.models().get(0);
        String effectiveLevel = effectiveReasoningLevel(provider, model, requestedReasoningLevel);
        return new ResolvedLlm(provider.endpoint(), provider.apiKey(), model, provider.protocol(), effectiveLevel);
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
            SqlToolClient.SqlOutcome outcome = sqlToolClient.executeSqlDetailed(sql);
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
        return new ToolOutcome("ERROR: unknown tool " + name
                + ". 可用工具：execute_sql（只读 SQL）、read_service_logs（容器日志）", null, null, false);
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
    private StreamTurnResult streamTurn(List<WireMessage> messages, String requestedModel,
                                        String reasoningLevel, int round,
                                        ChatEventConsumer eventConsumer) {
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel), reasoningLevel);
        ObjectNode body = baseBody(true, llm);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        return streamUpstream(llm, body, (contentToken, reasoningToken) -> {
            if (reasoningToken != null) eventConsumer.reasoningDelta(round, reasoningToken);
            if (contentToken != null) eventConsumer.delta(contentToken);
        });
    }

    /** Forced-answer variant of {@link #streamTurn} for the final round (tool_choice=none). */
    private StreamTurnResult streamFinalAnswer(List<WireMessage> messages, String requestedModel,
                                               String reasoningLevel, int round,
                                               ChatEventConsumer eventConsumer) {
        ResolvedLlm llm = withLevel(resolveLlm(requestedModel), reasoningLevel);
        ObjectNode body = baseBody(true, llm);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        // final round: the model must answer, not call more tools
        body.put("tool_choice", "none");
        return streamUpstream(llm, body, (contentToken, reasoningToken) -> {
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
     */
    private StreamTurnResult streamUpstream(ResolvedLlm llm, ObjectNode body, TokenSink sink) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // tool_calls accumulation: index → {id, name, args-builder} (fragments arrive out of order)
        Map<Integer, String> callIds = new HashMap<>();
        Map<Integer, String> callNames = new HashMap<>();
        Map<Integer, StringBuilder> callArgs = new HashMap<>();
        List<JsonNode> orderedCalls = new ArrayList<>();
        TokenUsage usage = null;

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(stripTrailingSlash(llm.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llm.apiKey())
                    .header("Accept", MediaType.ALL_VALUE)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String err = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return new StreamTurnResult(true, "上游 " + response.statusCode() + ": "
                        + abbreviate(err, 300), "", "", null, List.of(), null);
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
                            if (fn.has("name") && fn.path("name").asText().length() > 0) {
                                callNames.merge(idx, fn.path("name").asText(), String::concat);
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
            return new StreamTurnResult(false, null, content.toString(), reasoning.toString(),
                    assistant, orderedCalls, usage);
        } catch (Exception e) {
            return new StreamTurnResult(true, e.getMessage(), content.toString(), reasoning.toString(),
                    null, List.of(), usage);
        }
    }

    private static java.util.Set<Integer> unionKeys(Map<Integer, String> a, Map<Integer, StringBuilder> b) {
        java.util.Set<Integer> all = new java.util.TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        return all;
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
        if (!"openai".equalsIgnoreCase(llm.protocol())) return;
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
        return new ResolvedLlm(llm.baseUrl(), llm.apiKey(), llm.model(), llm.protocol(), reasoningLevel);
    }

    /** Returns a copy of the resolved endpoint carrying the given reasoning level. */

    /** Package-visible for tests; never returned outside the service. */
    record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,
                       /** 生效思考等级(已合并请求级与设置页默认);null = auto */
                       String effectiveReasoningLevel) {}

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

        return tools;
    }

    private List<WireMessage> buildMessages(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<CitationDto> citations,
                                            List<String> reflections) {
        List<WireMessage> messages = new ArrayList<>();
        messages.add(WireMessage.system(objectMapper, systemPromptWith(citations)));
        if (reflections != null && !reflections.isEmpty()) {
            messages.add(WireMessage.system(objectMapper, "此前类似任务的失败反思（仅作参考）：\n- " + String.join("\n- ", reflections)));
        }

        int from = Math.max(0, history.size() - 6);
        for (int i = from; i < history.size(); i++) {
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

    private String systemPromptWith(List<CitationDto> citations) {
        if (citations.isEmpty()) {
            return SYSTEM_PROMPT;
        }
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n以下是知识库检索到的相关片段：\n");
        for (CitationDto c : citations) {
            sb.append("[[").append(c.docName()).append("#chunk").append(c.chunkIndex())
                    .append("]] ").append(c.snippet()).append('\n');
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String abbreviateStatic(String text, int max) {
        return abbreviate(text, max);
    }

    /** Callbacks for the SSE events of one chat turn. */
    public interface ChatEventConsumer {

        void step(ChatStepDto step);

        void delta(String token);

        default void reasoningDelta(Integer roundIndex, String token) {
        }

        void sources(List<CitationDto> citations);
    }

    /** Result of one chat turn. */
    public record ChatTurn(String answer, List<CitationDto> citations, TokenUsage usage) {
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
