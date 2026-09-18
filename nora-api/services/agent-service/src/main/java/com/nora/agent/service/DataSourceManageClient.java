package com.nora.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 经 datasource-service({@code POST/GET/DELETE /api/datasources})管理数据源
 * 连接,供 agent 的 {@code manage_datasource} 工具。
 *
 * <p>安全:工具参数(可能含明文密码)会随步骤持久化并回显给 LLM——
 * 因此 {@link #create} 绝不记录或返回密码,编排层在持久化步骤前会脱敏
 * (见 ChatOrchestrationService)。
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
     * 创建连接并立即测试,让模型无需第二轮工具即可自我纠正
     * (harness 原则:错误即教学)。
     *
     * @return 带创建 id + 连通结果的面向 LLM 渲染
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

    /** 测试已存凭证;返回 ok/message/latency。 */
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

    /** 移除连接(历史在服务端级联删除)。 */
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
     * 数据源名单摘要(2026-09-18 上下文注入用):名称+引擎一行一个——
     * 注入系统提示后模型不必先 list 探索,直接用正确名字写 SQL。
     * 失败返回 null(注入是 best-effort,不阻断对话)。
     */
    public String nameSummary() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/datasources")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()
                    || envelope.data().isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                sb.append("- ").append(n.path("name").asText("?"))
                        .append("(").append(n.path("engine").asText("?"))
                        .append(", ").append(n.path("database").asText("?")).append(")\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.debug("datasource name summary failed (ignored): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 列出连接(密码脱敏)——这段渲染也是编排层给 {@code datasource} 参数
     * 的枚举提示。
     *
     * @return "id | name | engine | host:port/database | status" 行
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
     * 连接的表/列结构(GET /{id}/schema),紧凑渲染,让模型无需探索性查询
     * 就能规划 SQL。
     *
     * @param datasourceId 连接 id 或名称;null/空 = 第一个配置的连接
     * @return "schema.table(col type, ...)" 行,或一行错误
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

    /** ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }
}
