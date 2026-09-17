package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 经 datasource-service({@code POST /api/datasources/{id}/execute})执行已批准
 * 的写 SQL。强制守卫在服务端(WriteGuard);本客户端只转发结果。
 */
@Service
public class WriteSqlClient {

    private static final Logger log = LoggerFactory.getLogger(WriteSqlClient.class);

    private final RestClient restClient;

    private final SqlToolClient sqlToolClient;

    public WriteSqlClient(RestClient datasourceServiceRestClient, SqlToolClient sqlToolClient) {
        this.restClient = datasourceServiceRestClient;
        this.sqlToolClient = sqlToolClient;
    }

    /**
     * @return 面向 LLM 的渲染("rows_affected: N ...")或 "ERROR: ..."
     */
    public String executeWrite(String sql) {
        return executeWrite(sql, null);
    }

    /**
     * @param sql          单条写语句
     * @param datasourceId 显式连接(id 或名称);null = 第一个配置的连接
     * @return 面向 LLM 的渲染("rows_affected: N ...")或 "ERROR: ..."
     */
    public String executeWrite(String sql, String datasourceId) {
        try {
            Long connectionId = sqlToolClient.resolveConnectionId(datasourceId);
            if (connectionId == null) {
                return "ERROR: no database connection is configured in the datasource service"
                        + (datasourceId == null ? "" : " (查询目标: " + datasourceId + ")");
            }
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/datasources/{id}/execute", connectionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new WriteRequest(sql))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                String message = envelope == null ? "empty response" : envelope.message();
                return "ERROR: " + message;
            }
            int affected = envelope.data().path("rowCount").asInt(-1);
            long durationMs = envelope.data().path("durationMs").asLong(0);
            return "rows_affected: " + affected + " (" + durationMs + "ms)";
        } catch (Exception e) {
            log.warn("write sql failed: {}", e.getMessage());
            return "ERROR: " + (e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    /** POST 请求体。 */
    record WriteRequest(String sql) {
    }

    /** ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }
}
