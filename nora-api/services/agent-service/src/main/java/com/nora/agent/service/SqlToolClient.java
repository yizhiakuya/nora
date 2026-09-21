package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nora.common.response.ApiResponse;

/**
 * 对 datasource-service 执行受控只读 SQL
 * ({@code POST /api/datasources/{id}/query})。
 *
 * <p>ACI 原则(architecture-v2.md 4.8.3 节):返回给 LLM 的结果有界且带类型
 * ——行数上限由提供方强制(200),这里的渲染补一行列名表头并标记 NULL 单元格,
 * 模型无需看原始 JSON 就能解读。
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
     * 在第一个配置的连接上执行只读语句。
     *
     * @param sql SELECT / SHOW / EXPLAIN 语句
     * @return 面向 LLM 的渲染:表头 + 对齐的行,或一行错误
     */
    public String executeSql(String sql) {
        return executeSqlDetailed(sql).content();
    }

    /**
     * 同 {@link #executeSql(String)},但额外报告提供方行数上限,让编排层把
     * 步骤标为 {@code truncated}——静默截断会让模型把部分行当成完整答案。
     *
     * @param sql          SELECT / SHOW / EXPLAIN 语句
     * @param datasourceId 显式连接(id 或名称);null = 第一个配置的连接
     */
    public SqlOutcome executeSqlDetailed(String sql, String datasourceId) {
        try {
            Long connectionId = resolveConnectionId(datasourceId);
            if (connectionId == null) {
                return new SqlOutcome("ERROR: no database connection is configured in the datasource service"
                        + (datasourceId == null ? "" : " (查询目标: " + datasourceId + ")"),
                        null, false);
            }
            ApiResponse<QueryBody> envelope = restClient.post()
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

    /** 兼容:第一个配置的连接。 */
    public SqlOutcome executeSqlDetailed(String sql) {
        return executeSqlDetailed(sql, null);
    }

    /**
     * 把 {@code datasource} 参数解析为连接 id:数字 → id,否则对连接显示名
     * 或库名做不区分大小写匹配(模型习惯传库名);其余情况回退第一个连接
     * 而不是报错——该参数只用于消歧,猜错不该阻塞查询。
     *
     * @return 连接 id;完全没配连接时为 null
     */
    public Long resolveConnectionId(String datasourceId) {
        try {
            ApiResponse<ArrayNode> envelope = restClient.get()
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

    /** 渲染后的 SQL 结果 + 面向 UI 的元数据。 */
    public record SqlOutcome(String content, String summary, boolean truncated) {
    }

    /**
     * 为常见 DB 错误补充正确的替代语法,让模型下一轮自我纠正
     * (harness 原则:错误即教学)。
     */
    private String hint(String message) {
        if (message != null && message.contains("unrecognized configuration parameter")) {
            // MySQL 的 SHOW TABLES / USE db 等在 PostgreSQL 上不存在
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

    /** 把列+行渲染为紧凑 TSV,LLM 一眼可读。 */
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

    /** POST query 请求体。 */
    record QueryRequest(String sql) {
    }

    /** 查询结果载荷(ApiResponse 信封的 data)。 */
    record QueryBody(
            java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows,
            int rowCount,
            long durationMs,
            Boolean truncated) {
    }
}
