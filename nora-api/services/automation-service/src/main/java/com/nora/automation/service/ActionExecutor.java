package com.nora.automation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Executes automation actions.
 *
 * <p>v1 supports a single action type: {@code sql} — a guarded read-only
 * statement run against datasource-service (which enforces SELECT/SHOW/
 * EXPLAIN, row limits and timeouts). The execution detail is the rendered
 * result, stored on the execution record.
 */
@Service
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ActionExecutor(RestClient datasourceServiceRestClient, ObjectMapper objectMapper) {
        this.restClient = datasourceServiceRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the action payload.
     *
     * @param actionJson stored JSONB action, e.g. {@code {"type":"sql","sql":"SELECT ..."}}
     * @return execution detail (success rendering or ERROR: line); never throws
     */
    public String execute(String actionJson) {
        try {
            JsonNode action = objectMapper.readTree(actionJson);
            String type = action.path("type").asText("sql");
            if (!"sql".equals(type)) {
                return "ERROR: unsupported action type " + type;
            }
            String sql = action.path("sql").asText("");
            if (sql.isBlank()) {
                return "ERROR: action sql is empty";
            }
            Long connectionId = action.hasNonNull("connectionId")
                    ? action.get("connectionId").asLong() : firstConnectionId();
            if (connectionId == null) {
                return "ERROR: no database connection configured";
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

    /** Builds the stored action JSON for a SQL rule. */
    public String sqlAction(String sql) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "sql");
        node.put("sql", sql);
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
