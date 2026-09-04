package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Executes guarded read-only SQL against datasource-service
 * ({@code POST /api/datasources/{id}/query}).
 *
 * <p>ACI principle (architecture-v2.md section 4.8.3): the result returned
 * to the LLM is bounded and typed — max rows are already enforced by the
 * provider (200), and the rendering here adds a header line with column
 * names and marks NULL cells, so the model can interpret the payload
 * without seeing raw JSON.
 */
@Service
public class SqlToolClient {

    private static final Logger log = LoggerFactory.getLogger(SqlToolClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SqlToolClient(RestClient datasourceServiceRestClient, ObjectMapper objectMapper) {
        this.restClient = datasourceServiceRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs a read-only statement on the first configured connection.
     *
     * @param sql SELECT / SHOW / EXPLAIN statement
     * @return LLM-friendly rendering: header + aligned rows, or an error line
     */
    public String executeSql(String sql) {
        try {
            // Phase 3: single-connection workbench — use the first connection row
            Long connectionId = firstConnectionId();
            if (connectionId == null) {
                return "ERROR: no database connection is configured in the datasource service";
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
                log.warn("datasource query rejected: {}", message);
                return "ERROR: " + message;
            }
            return render(envelope.data());
        } catch (Exception e) {
            log.warn("datasource query failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    private Long firstConnectionId() {
        try {
            Envelope<ArrayNode> envelope = restClient.get()
                    .uri("/api/datasources")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null
                    || envelope.data().isEmpty()) {
                return null;
            }
            JsonNode first = envelope.data().get(0);
            return first.has("id") ? first.get("id").asLong() : null;
        } catch (Exception e) {
            log.warn("datasource list failed: {}", e.getMessage());
            return null;
        }
    }

    /** Renders columns+rows as a compact TSV the LLM can read at a glance. */
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

    /** POST query body. */
    record QueryRequest(String sql) {
    }

    /** Query result payload (the data of the ApiResponse envelope). */
    record QueryBody(
            java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows,
            int rowCount,
            long durationMs) {
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }

    /** ObjectNode helper for list endpoint. */
    record ObjectEnvelope(ObjectNode data) {
    }
}
