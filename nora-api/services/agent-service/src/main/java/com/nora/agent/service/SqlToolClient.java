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
        return executeSqlDetailed(sql).content();
    }

    /**
     * Like {@link #executeSql(String)} but also reports the provider-side row
     * cap so the orchestrator can mark the step {@code truncated} — a silent
     * cap makes the model present partial rows as the full answer.
     *
     * @param sql          SELECT / SHOW / EXPLAIN statement
     * @param datasourceId explicit connection (id or name); null = first configured
     */
    public SqlOutcome executeSqlDetailed(String sql, String datasourceId) {
        try {
            Long connectionId = resolveConnectionId(datasourceId);
            if (connectionId == null) {
                return new SqlOutcome("ERROR: no database connection is configured in the datasource service"
                        + (datasourceId == null ? "" : " (查询目标: " + datasourceId + ")"),
                        null, false);
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
                return new SqlOutcome("ERROR: " + hint(message), null, false);
            }
            String rendered = render(envelope.data());
            int rowCount = envelope.data().rowCount();
            return new SqlOutcome(rendered,
                    rowCount + " rows, " + envelope.data().durationMs() + "ms",
                    Boolean.TRUE.equals(envelope.data().truncated()));
        } catch (Exception e) {
            log.warn("datasource query failed: {}", e.getMessage());
            return new SqlOutcome("ERROR: " + hint(e.getMessage() == null ? "unknown error" : e.getMessage()),
                    null, false);
        }
    }

    /** Back-compat: first configured connection. */
    public SqlOutcome executeSqlDetailed(String sql) {
        return executeSqlDetailed(sql, null);
    }

    /**
     * Resolves the {@code datasource} arg to a connection id: numeric → id,
     * else case-insensitive match against the connection display name OR the
     * database name (models habitually pass the db name); anything else
     * falls back to the first connection rather than failing — the arg only
     * disambiguates, a wrong guess must not block the query.
     *
     * @return connection id, or null when no connection is configured at all
     */
    public Long resolveConnectionId(String datasourceId) {
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
            if (datasourceId == null || datasourceId.isBlank()) {
                return envelope.data().get(0).path("id").asLong();
            }
            String target = datasourceId.trim();
            if (target.matches("\\d+")) {
                long id = Long.parseLong(target);
                for (JsonNode n : envelope.data()) {
                    if (n.path("id").asLong(-1) == id) {
                        return id;
                    }
                }
            } else {
                for (JsonNode n : envelope.data()) {
                    if (target.equalsIgnoreCase(n.path("name").asText(""))
                            || target.equalsIgnoreCase(n.path("database").asText(""))) {
                        return n.path("id").asLong();
                    }
                }
                // 前缀/包含匹配(模型常传"nora-pg"这类缩写)
                for (JsonNode n : envelope.data()) {
                    String name = n.path("name").asText("");
                    if (!name.isBlank() && (name.toLowerCase().startsWith(target.toLowerCase())
                            || name.toLowerCase().contains(target.toLowerCase()))) {
                        return n.path("id").asLong();
                    }
                }
            }
            // 未匹配到:回落第一个连接(参数只是提示,猜错不该阻断查询)
            return envelope.data().get(0).path("id").asLong();
        } catch (Exception e) {
            log.warn("datasource list failed: {}", e.getMessage());
            return null;
        }
    }

    /** Rendered SQL result plus UI-facing metadata. */
    public record SqlOutcome(String content, String summary, boolean truncated) {
    }

    /**
     * Enriches common DB errors with the correct alternative syntax so the
     * model can self-correct on the next round (harness: errors teach).
     */
    private String hint(String message) {
        if (message != null && message.contains("unrecognized configuration parameter")) {
            // MySQL SHOW TABLES / USE db etc. don't exist on PostgreSQL
            return message + "（提示:目标数据库是 PostgreSQL,不支持 MySQL 的 SHOW 语法。"
                    + "查表用 SELECT tablename FROM pg_tables WHERE schemaname = 'public';"
                    + "查库用 SELECT datname FROM pg_database;查列用 SELECT column_name, data_type "
                    + "FROM information_schema.columns WHERE table_name = '表名'）";
        }
        if (message != null && message.contains("does not exist") && message.contains("relation")) {
            return message + "（提示:表不存在或不在默认 schema。"
                    + "先用 SELECT tablename FROM pg_tables 确认表名,跨 schema 时用 schema.table 限定）";
        }
        return message;
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
            long durationMs,
            Boolean truncated) {
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }

    /** ObjectNode helper for list endpoint. */
    record ObjectEnvelope(ObjectNode data) {
    }
}
