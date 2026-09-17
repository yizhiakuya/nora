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
        try {
            JsonNode action = objectMapper.readTree(actionJson);
            String type = action.path("type").asText("sql");
            if ("agent".equals(type)) {
                return executeAgent(action.path("prompt").asText(""));
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

    /** 走 agent-service 的一次性运行端点执行自然语言指令;回答即执行详情。 */
    private String executeAgent(String prompt) {
        if (prompt.isBlank()) {
            return "ERROR: action prompt is empty";
        }
        try {
            java.util.Map<String, Object> out = agentRestClient.post()
                    .uri("/api/chat/agent/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("prompt", prompt))
                    .retrieve()
                    .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() {
                    });
            // agent-service 以 status 字段标明成败(答案文本可能是任意内容,不能靠
            // "ERROR:" 前缀猜;模型未配置时也返回 status=error)
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
            // 5xx 时 RestClient 只给状态行,响应体里的具体错误/操作提示被丢掉;
            // 读出 body 一起返回,错误才可操作(符合仓库「错误带 hint」约定)
            String hint = "";
            if (e instanceof org.springframework.web.client.RestClientResponseException restEx) {
                hint = " | " + restEx.getResponseBodyAsString();
            }
            return "ERROR: agent run failed: " + e.getMessage() + hint;
        }
    }

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
