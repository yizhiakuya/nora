package com.nora.automation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 执行自动化动作。
 *
 * 支持的动作类型:
 * <ul>
 *   <li>{@code sql} — 受保护的只读查询,经 datasource-service 执行
 *       (只允许 SELECT/SHOW/EXPLAIN,有行数上限和超时)</li>
 *   <li>{@code agent} — 自然语言指令,由 agent-service 一次性运行端点执行
 *       (RAG + 工具循环,FULL 权限档);回答即执行详情</li>
 * </ul>
 * 执行详情存储在执行记录上。
 */
@Service
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final RestClient restClient;
    private final RestClient agentRestClient;
    private final ObjectMapper objectMapper;

    public ActionExecutor(RestClient datasourceServiceRestClient, RestClient agentServiceRestClient,
                          ObjectMapper objectMapper) {
        this.restClient = datasourceServiceRestClient;
        this.agentRestClient = agentServiceRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 运行动作载荷。
     *
     * @param actionJson 存储的 JSONB 动作,如 {@code {"type":"sql","sql":"SELECT ..."}}
     * @return 执行细节(成功渲染或 ERROR: 行);绝不抛异常
     */
    public String execute(String actionJson) {
        return execute(actionJson, null, null);
    }

    /**
     * 带会话信息的执行(2026-09-20,定时任务=往会话发消息):
     * agent 动作把运行落进规则专属会话({@code sessionId}),消息 sender=automation、
     * AI 回答/步骤照常持久化——用户在会话列表就能看到定时任务的完整记录。
     * sessionId 为空时保持旧行为(无会话静默运行)。
     */
    public String execute(String actionJson, String sessionId, String sessionTitle) {
        try {
            JsonNode action = objectMapper.readTree(actionJson);
            String type = action.path("type").asText("sql");
            if ("agent".equals(type)) {
                return executeAgent(action.path("prompt").asText(""), sessionId, sessionTitle);
            }
            if (!"sql".equals(type)) {
                return "ERROR: unsupported action type " + type;
            }
            String sql = action.path("sql").asText("");
            if (sql.isBlank()) {
                return "ERROR: action sql is empty";
            }
            // 显式 Long:三元两分支类型不一致时会自动拆箱,null 会当场 NPE,
            // 让下面的判空形同虚设(真实踩过的坑)
            Long connectionId = action.hasNonNull("connectionId")
                    ? Long.valueOf(action.get("connectionId").asLong()) : firstConnectionId();
            if (connectionId == null) {
                return "ERROR: no database connection configured — 请先在数据源页添加一个连接";
            }
            Envelope<QueryBody> envelope = restClient.post()
                    .uri("/api/datasources/{id}/query", connectionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new QueryRequest(sql))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                String message = envelope == null ? "empty response" : envelope.message();
                return "ERROR: " + message;
            }
            return render(envelope.data());
        } catch (Exception e) {
            log.warn("action execution failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 执行自然语言指令(定时任务=往会话发消息,2026-09-20 用户明确语义):
     * **直接复用正常对话端点** {@code POST /sessions/{id}/messages}(SSE)——步骤
     * 收集、推理聚合、消息落库、运行生命周期全部走 ChatTurnRunner 同一套引擎,
     * 本方法只做「发一条消息(sender=automation)+ 取回最终回答」。
     *
     * <p>sessionId 非空时必走对话端点;为空(旧调用方/无会话场景)回退
     * {@code /agent/run} 无会话静默运行(不落库)。
     */
    private String executeAgent(String prompt, String sessionId, String sessionTitle) {
        if (prompt.isBlank()) {
            return "ERROR: action prompt is empty";
        }
        if (sessionId == null || sessionId.isBlank()) {
            return executeAgentUnattended(prompt);
        }
        try {
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("content", prompt);
            body.put("sender", "automation");
            body.put("unattended", true);
            body.put("sessionTitle", sessionTitle == null ? "定时任务" : sessionTitle);
            // SSE 响应:逐行解析,收集 delta 拼最终回答、error 取错误
            String answer = agentRestClient.post()
                    .uri("/api/chat/sessions/{id}/messages", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(body)
                    .exchange((req, res) -> {
                        // exchange 不走 defaultStatusHandler:非 2xx(如 409 会话忙)
                        // 在这里显式读错误体并抛出,提示可操作
                        if (res.getStatusCode().isError()) {
                            String errBody = new String(res.getBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            throw new IllegalStateException("HTTP " + res.getStatusCode().value() + ": " + errBody);
                        }
                        StringBuilder answerBuf = new StringBuilder();
                        StringBuilder errorBuf = new StringBuilder();
                        String event = "";
                        try (var reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(res.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("event:")) {
                                    event = line.substring(6).trim();
                                } else if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    if ("delta".equals(event)) {
                                        answerBuf.append(extractContent(data));
                                    } else if ("error".equals(event) && errorBuf.length() == 0) {
                                        errorBuf.append(extractMessage(data));
                                    }
                                }
                            }
                        }
                        if (errorBuf.length() > 0 && answerBuf.length() == 0) {
                            throw new IllegalStateException(errorBuf.toString());
                        }
                        return answerBuf.toString();
                    });
            return answer == null || answer.isBlank() ? "ERROR: agent returned empty answer" : answer;
        } catch (Exception e) {
            log.warn("agent action failed: {}", e.getMessage());
            // 超时/断线时结果未知(2026-09-21):agent 那一轮可能仍在后台跑完并
            // 落库——不能把"客户端断开"冒充成"任务失败"(语义对齐对话轮次的
            // unknown 状态)。给"去会话里看结果"的可操作指引,不诱导盲目重跑。
            boolean transport = e instanceof java.io.IOException
                    || (e.getMessage() != null && (e.getMessage().contains("timed out")
                        || e.getMessage().contains("Read timed out") || e.getMessage().contains("timeout")));
            // 错误体里的具体提示透传,错误才可操作(符合仓库「错误带 hint」约定)
            String hint = "";
            if (e instanceof org.springframework.web.client.RestClientResponseException restEx) {
                hint = " | " + restEx.getResponseBodyAsString();
            }
            if (transport) {
                return "ERROR: 与 agent 的连接中断(" + e.getMessage() + ")——结果未知:"
                        + "该轮可能仍在后台运行,完成后会照常写入规则专属会话(「定时任务:规则名」)。"
                        + "请到会话里确认是否已完成,确认未完成再手动重跑;不要直接假定失败";
            }
            return "ERROR: agent run failed: " + e.getMessage() + hint;
        }
    }

    /** 无会话一次性运行(旧路径,env-service 诊断同款;不落库)。 */
    private String executeAgentUnattended(String prompt) {
        try {
            java.util.Map<String, Object> out = agentRestClient.post()
                    .uri("/api/chat/agent/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("prompt", prompt))
                    .retrieve()
                    .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() {
                    });
            if (out == null) {
                return "ERROR: empty agent response";
            }
            if (!"completed".equals(out.get("status"))) {
                Object error = out.get("error");
                return "ERROR: agent run failed: "
                        + (error == null ? "no status in response" : error);
            }
            Object answer = out.get("answer");
            return answer == null ? "ERROR: agent returned null answer" : answer.toString();
        } catch (Exception e) {
            log.warn("agent action failed: {}", e.getMessage());
            String hint = "";
            if (e instanceof org.springframework.web.client.RestClientResponseException restEx) {
                hint = " | " + restEx.getResponseBodyAsString();
            }
            return "ERROR: agent run failed: " + e.getMessage() + hint;
        }
    }

    /** 从 SSE data JSON 里提取 delta 文本({@code {"content":"…"}});失败返回空串。 */
    private static String extractContent(String data) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    MAPPER.readTree(data);
            return node.path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 SSE error JSON 里提取 message;失败返回原始串。 */
    private static String extractMessage(String data) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    MAPPER.readTree(data);
            String message = node.path("message").asText("");
            return message.isBlank() ? data : message;
        } catch (Exception e) {
            return data;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private Long firstConnectionId() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/datasources")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null
                    || !envelope.data().isArray() || envelope.data().isEmpty()) {
                return null;
            }
            return envelope.data().get(0).path("id").asLong(0);
        } catch (Exception e) {
            log.warn("datasource list failed: {}", e.getMessage());
            return null;
        }
    }

    private String render(QueryBody result) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", result.columns())).append('\n');
        for (java.util.List<String> row : result.rows()) {
            sb.append(row.stream().map(c -> c == null ? "NULL" : c)
                    .reduce((a, b) -> a + "\t" + b).orElse("")).append('\n');
        }
        sb.append("(").append(result.rowCount()).append(" rows, ")
                .append(result.durationMs()).append("ms)");
        return sb.toString();
    }

    /** 为 SQL 规则构建存储的动作 JSON。 */
    public String sqlAction(String sql) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "sql");
        node.put("sql", sql);
        return node.toString();
    }

    /** 为 agent(自然语言)规则构建存储的动作 JSON。 */
    public String agentAction(String prompt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "agent");
        node.put("prompt", prompt);
        return node.toString();
    }

    record QueryRequest(String sql) {
    }

    record QueryBody(
            java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows,
            int rowCount,
            long durationMs) {
    }

    record Envelope<T>(int code, T data, String message) {
    }
}
