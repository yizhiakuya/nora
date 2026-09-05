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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chat orchestration with an OpenAI function-calling agent loop:
 * <ol>
 *   <li>RAG retrieval (unchanged) → system prompt citations</li>
 *   <li>Tool loop: non-streaming rounds where the model may call
 *       {@code execute_sql}; each call runs the guarded query on
 *       datasource-service and feeds the result back (max 5 rounds)</li>
 *   <li>Final answer streams token by token to the SSE consumer</li>
 * </ol>
 *
 * <p>The tool protocol is hand-rolled against the OpenAI wire format via
 * RestClient: the relay supports function calling, and keeping the loop
 * explicit lets every step surface as a {@code step} SSE event.
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
            """;

    /** Max tool rounds per chat turn (ReAct depth guard). */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final LlmProperties llmProperties;
    private final RagRetrievalClient ragRetrievalClient;
    private final SqlToolClient sqlToolClient;
    private final ServiceLogClient serviceLogClient;
    private final ObjectMapper objectMapper;
    private final RestClient llmClient;

    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient,
                                    SqlToolClient sqlToolClient,
                                    ServiceLogClient serviceLogClient,
                                    ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.ragRetrievalClient = ragRetrievalClient;
        this.sqlToolClient = sqlToolClient;
        this.serviceLogClient = serviceLogClient;
        this.objectMapper = objectMapper;
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
        // Step 1: knowledge retrieval (best-effort, before the LLM call)
        long retrievalStart = System.currentTimeMillis();
        List<CitationDto> citations = ragRetrievalClient.search(userMessage, 6);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        if (!citations.isEmpty()) {
            eventConsumer.step(new ChatStepDto(
                    "s-rag", "tool", "检索知识库",
                    "召回 " + citations.size() + " 个相关片段（" + citations.get(0).docName() + " 等）",
                    retrievalMs, "completed"));
            eventConsumer.sources(citations);
        }

        // Step 2: tool loop (non-streaming rounds)
        List<WireMessage> messages = buildMessages(userMessage, history, citations);
        int stepCounter = 1;
        String toolOutcome = null;
        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                String stepId = "s-tool-" + stepCounter;
                long callStart = System.currentTimeMillis();
                eventConsumer.step(new ChatStepDto(
                        stepId, "tool", "模型决策", "第 " + (round + 1) + " 轮工具调用", null, "running"));
                WireCompletion completion = complete(messages);
                if (completion == null || completion.message == null) {
                    eventConsumer.step(new ChatStepDto(stepId, "tool", "模型决策",
                            "空响应", System.currentTimeMillis() - callStart, "failed"));
                    break;
                }
                if (completion.toolCalls == null || completion.toolCalls.isEmpty()) {
                    // No tool request: use this content directly, no streaming round needed
                    eventConsumer.step(new ChatStepDto(stepId, "think", "生成回答", null,
                            System.currentTimeMillis() - callStart, "completed"));
                    String content = completion.message.has("content")
                            ? completion.message.get("content").asText("") : "";
                    if (!content.isBlank()) {
                        // deliver as one delta so the UI stream completes
                        eventConsumer.delta(content);
                        return CompletableFuture.completedFuture(new ChatTurn(content, citations));
                    }
                    break;
                }
                // Execute each requested tool call
                messages.add(new WireMessage(completion.message));
                for (JsonNode call : completion.toolCalls) {
                    String callId = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String args = call.path("function").path("arguments").asText("{}");
                    eventConsumer.step(new ChatStepDto(stepId, "tool", "调用工具 " + name,
                            abbreviate(args, 80), System.currentTimeMillis() - callStart, "completed"));
                    String result = executeTool(name, args);
                    toolOutcome = result;
                    ObjectNode toolMsg = objectMapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", callId);
                    toolMsg.put("content", result);
                    messages.add(new WireMessage(toolMsg));
                }
                stepCounter++;
            }
        } catch (Exception e) {
            log.warn("tool loop failed, falling back to plain answer: {}", e.getMessage());
        }

        // Step 3: streaming final answer (with tool outcome already in the messages)
        long answerStart = System.currentTimeMillis();
        String answerStepId = "s-answer";
        final String fallbackOutcome = toolOutcome;
        eventConsumer.step(new ChatStepDto(answerStepId, "think", "生成回答", null, null, "running"));

        List<WireMessage> finalMessages = messages;
        CompletableFuture<ChatTurn> future = new CompletableFuture<>();
        StringBuilder answer = new StringBuilder();

        streamComplete(finalMessages, new StreamHandler() {
            @Override
            public void onDelta(String token) {
                answer.append(token);
                eventConsumer.delta(token);
            }

            @Override
            public void onComplete() {
                eventConsumer.step(new ChatStepDto(answerStepId, "think", "生成回答", null,
                        System.currentTimeMillis() - answerStart, "completed"));
                future.complete(new ChatTurn(answer.toString(), citations));
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM stream failed: {}", error.getMessage());
                // fall back to the last tool outcome as the answer instead of a dead stream
                String fallback = fallbackOutcome != null
                        ? "查询结果：\n```\n" + fallbackOutcome + "\n```"
                        : "";
                if (!fallback.isBlank()) {
                    eventConsumer.delta(fallback);
                    future.complete(new ChatTurn(fallback, citations));
                    eventConsumer.step(new ChatStepDto(answerStepId, "think", "生成回答",
                            "流式失败,已返回工具结果", System.currentTimeMillis() - answerStart, "completed"));
                } else {
                    eventConsumer.step(new ChatStepDto(answerStepId, "think", "生成回答",
                            "模型调用失败：" + error.getMessage(),
                            System.currentTimeMillis() - answerStart, "failed"));
                    future.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    /** Whether an LLM API key is configured. */
    public boolean configured() {
        return llmProperties.configured();
    }

    private String executeTool(String name, String args) {
        if ("execute_sql".equals(name)) {
            String sql = extractStringArg(args, "sql");
            return bounded(sqlToolClient.executeSql(sql));
        }
        if ("read_service_logs".equals(name)) {
            String service = extractStringArg(args, "service");
            int limit = 50;
            try {
                JsonNode node = parseArgs(args);
                if (node.has("limit") && node.get("limit").isNumber()) {
                    limit = Math.min(node.get("limit").asInt(50), 100);
                }
            } catch (Exception ignored) {
                // keep default limit
            }
            return bounded(serviceLogClient.readLogs(service, limit));
        }
        return "ERROR: unknown tool " + name;
    }

    /** 工具结果上限 8KB:长日志会拖慢上游模型且占上下文(ACI 有界负载原则) */
    private static String bounded(String result) {
        if (result == null) {
            return "(empty)";
        }
        if (result.length() <= 8192) {
            return result;
        }
        return result.substring(0, 8192) + "\n…(结果已截断)";
    }

    private JsonNode parseArgs(String argsJson) throws Exception {
        return objectMapper.readTree(argsJson);
    }

    private String extractStringArg(String argsJson, String field) {
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            return node.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** Non-streaming completion round that may return tool_calls. */
    private WireCompletion complete(List<WireMessage> messages) {
        ObjectNode body = baseBody(false);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        JsonNode response = llmClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llmProperties.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return null;
        }
        JsonNode choice = response.path("choices").path(0).path("message");
        if (choice.isMissingNode()) {
            return null;
        }
        return new WireCompletion((ObjectNode) choice,
                choice.has("tool_calls") ? choice.get("tool_calls") : null);
    }

    /** Streaming final answer. */
    private void streamComplete(List<WireMessage> messages, StreamHandler handler) {
        ObjectNode body = baseBody(true);
        body.set("messages", messagesArray(messages));
        body.set("tools", toolsSpec());
        // final round: the model must answer, not call more tools
        body.put("tool_choice", "none");
        CompletableFuture.runAsync(() -> {
            try {
                String raw = llmClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + llmProperties.apiKey())
                        .body(body)
                        .retrieve()
                        .body(String.class);
                parseSseStream(raw, handler);
            } catch (Exception e) {
                handler.onError(e);
            }
        });
    }

    /** Parses an SSE text payload from the OpenAI-compatible endpoint. */
    private void parseSseStream(String raw, StreamHandler handler) {
        if (raw == null) {
            handler.onError(new IllegalStateException("empty stream"));
            return;
        }
        try {
            for (String block : raw.split("\n\n")) {
                for (String line : block.split("\n")) {
                    line = line.trim();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        handler.onComplete();
                        return;
                    }
                    JsonNode delta = objectMapper.readTree(payload)
                            .path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        handler.onDelta(delta.asText());
                    }
                }
            }
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    private ObjectNode baseBody(boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llmProperties.model());
        body.put("stream", stream);
        return body;
    }

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
        sqlFn.put("description", "在已连接的数据库上执行只读 SQL 查询(仅 SELECT/SHOW/EXPLAIN),返回真实结果。需要数据库统计、表数据时必须使用此工具");
        ObjectNode sqlParams = sqlFn.putObject("parameters");
        sqlParams.put("type", "object");
        ObjectNode sqlProps = sqlParams.putObject("properties");
        ObjectNode sqlProp = sqlProps.putObject("sql");
        sqlProp.put("type", "string");
        sqlProp.put("description", "要执行的只读 SQL 语句");
        ArrayNode sqlRequired = sqlParams.putArray("required");
        sqlRequired.add("sql");
        tools.add(sqlTool);

        ObjectNode logTool = objectMapper.createObjectNode();
        logTool.put("type", "function");
        ObjectNode logFn = logTool.putObject("function");
        logFn.put("name", "read_service_logs");
        logFn.put("description", "读取本地 Docker 容器服务的最近日志。诊断服务异常/报错时使用;可用服务可先不带参数理解,或从用户上下文推断");
        ObjectNode logParams = logFn.putObject("parameters");
        logParams.put("type", "object");
        ObjectNode logProps = logParams.putObject("properties");
        ObjectNode serviceProp = logProps.putObject("service");
        serviceProp.put("type", "string");
        serviceProp.put("description", "容器名,如 nora-postgres / nora-redis / nora-nacos");
        ObjectNode limitProp = logProps.putObject("limit");
        limitProp.put("type", "integer");
        limitProp.put("description", "返回的最近日志行数,默认 50,最大 100");
        ArrayNode logRequired = logParams.putArray("required");
        logRequired.add("service");
        tools.add(logTool);

        return tools;
    }

    private List<WireMessage> buildMessages(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<CitationDto> citations) {
        List<WireMessage> messages = new ArrayList<>();
        messages.add(WireMessage.system(objectMapper, systemPromptWith(citations)));

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

    /** Callbacks for the SSE events of one chat turn. */
    public interface ChatEventConsumer {

        void step(ChatStepDto step);

        void delta(String token);

        void sources(List<CitationDto> citations);
    }

    /** Result of one chat turn. */
    public record ChatTurn(String answer, List<CitationDto> citations) {
    }

    /** One assistant completion from a tool round. */
    private record WireCompletion(ObjectNode message, JsonNode toolCalls) {
    }

    /** Stream handler for the final answer. */
    private interface StreamHandler {

        void onDelta(String token);

        void onComplete();

        void onError(Throwable error);
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
