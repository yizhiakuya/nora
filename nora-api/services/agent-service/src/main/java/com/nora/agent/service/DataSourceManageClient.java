package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages datasource connections via datasource-service
 * ({@code POST/GET/DELETE /api/datasources}) for the agent's
 * {@code manage_datasource} tool.
 *
 * <p>Security: the tool's args (which may contain the plaintext password)
 * are persisted with the step and echoed to the LLM — {@link #create}
 * therefore never logs or returns the password, and the orchestrator
 * redacts it before persisting the step (see ChatOrchestrationService).
 */
@Service
public class DataSourceManageClient {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManageClient.class);

    private final RestClient restClient;
    private final SqlToolClient sqlToolClient;

    public DataSourceManageClient(RestClient datasourceServiceRestClient, SqlToolClient sqlToolClient) {
        this.restClient = datasourceServiceRestClient;
        this.sqlToolClient = sqlToolClient;
    }

    /**
     * Creates a connection and immediately tests it so the model can
     * self-correct (harness: errors teach) without a second tool round.
     *
     * @return LLM-friendly rendering with the created id + connectivity result
     */
    public String create(String name, String engine, String host, Integer port,
                         String database, String username, String password) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("engine", engine.trim().toLowerCase());
            body.put("host", host);
            body.put("port", port);
            body.put("database", database);
            body.put("username", username);
            body.put("password", password == null ? "" : password);
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/datasources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            long id = envelope.data().path("id").asLong(-1);
            String status = envelope.data().path("status").asText("");
            StringBuilder sb = new StringBuilder("已创建数据源 id=" + id
                    + " name=" + envelope.data().path("name").asText(name)
                    + " engine=" + engine + " host=" + host + ":" + port
                    + " database=" + database + " status=" + status);
            if (id > 0) {
                sb.append('\n').append(test(id));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("datasource create failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** Tests stored credentials; returns ok/message/latency. */
    public String test(long id) {
        try {
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/datasources/{id}/test", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            return "连接测试: " + (envelope.data().path("ok").asBoolean() ? "成功" : "失败")
                    + " — " + envelope.data().path("message").asText("")
                    + (envelope.data().hasNonNull("latencyMs")
                       ? " (" + envelope.data().path("latencyMs").asLong() + "ms)" : "");
        } catch (Exception e) {
            log.warn("datasource test failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** Removes a connection (history cascades server-side). */
    public String remove(long id) {
        try {
            Envelope<JsonNode> envelope = restClient.delete()
                    .uri("/api/datasources/{id}", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            return "已删除数据源 id=" + id + "(其查询历史一并删除)";
        } catch (Exception e) {
            log.warn("datasource remove failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Lists connections with masked passwords — this rendering is what the
     * orchestrator also offers as the {@code datasource} arg enum hint.
     *
     * @return "id | name | engine | host:port/database | status" lines
     */
    public String list() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/datasources")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(尚无数据源)";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                sb.append("id=").append(n.path("id").asLong())
                        .append(" | ").append(n.path("name").asText("?"))
                        .append(" | ").append(n.path("engine").asText("?"))
                        .append(" | ").append(n.path("host").asText("?")).append(':')
                        .append(n.path("port").asInt(0))
                        .append('/').append(n.path("database").asText("?"))
                        .append(" | status=").append(n.path("status").asText("?"))
                        .append(" | user=").append(n.path("username").asText("?"))
                        .append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("datasource list failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Table/column structure of a connection (GET /{id}/schema), rendered
     * compactly so the model can plan SQL without exploratory queries.
     *
     * @param datasourceId connection id or name; null/blank = first configured
     * @return "schema.table(col type, ...)" lines, or an error line
     */
    public String schema(String datasourceId) {
        try {
            Long connectionId = sqlToolClient.resolveConnectionId(datasourceId);
            if (connectionId == null) {
                return "ERROR: no matching database connection" + (datasourceId == null ? "" : ": " + datasourceId)
                        + "。可用连接:\n" + list();
            }
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/datasources/{id}/schema", connectionId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode tables = envelope.data().path("tables");
            if (!tables.isArray() || tables.isEmpty()) {
                return "(该连接没有可见的表)";
            }
            StringBuilder sb = new StringBuilder();
            int tableCount = 0;
            for (JsonNode t : tables) {
                String schema = t.path("schema").asText("");
                String name = (schema.isBlank() ? "" : schema + ".") + t.path("name").asText("?");
                String tableComment = t.path("comment").asText("");
                StringBuilder cols = new StringBuilder();
                for (JsonNode c : t.path("columns")) {
                    if (cols.length() > 0) cols.append(", ");
                    cols.append(c.path("name").asText("?")).append(' ').append(c.path("type").asText(""));
                    // 注释/主键标记:帮助模型理解字段语义(PK 影响 JOIN 与 WHERE 计划)
                    if (c.path("primaryKey").asBoolean(false)) {
                        cols.append(" PK");
                    }
                    String colComment = c.path("comment").asText("");
                    if (!colComment.isBlank()) {
                        cols.append(" -- ").append(colComment);
                    }
                }
                sb.append(name);
                if (!tableComment.isBlank()) {
                    sb.append(" -- ").append(tableComment);
                }
                sb.append('(').append(cols).append(")\n");
                tableCount++;
                if (sb.length() > 20_000) {
                    sb.append("…[schema 过大已截断,已列 ").append(tableCount).append(" 张表,请用 SQL 查询剩余结构]\n");
                    break;
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("datasource schema failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
