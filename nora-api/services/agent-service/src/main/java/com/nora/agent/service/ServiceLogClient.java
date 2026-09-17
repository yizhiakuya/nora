package com.nora.agent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从 env-service 取容器日志
 * ({@code GET /api/environment/services} + {@code POST .../start|stop|restart})。
 *
 * <p>ACI 原则:喂给 LLM 的载荷是近期尾部、去掉空行、有行数预算——
 * 不是无界的消防水带。
 */
@Service
public class ServiceLogClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceLogClient.class);

    private final RestClient restClient;

    public ServiceLogClient(RestClient envServiceRestClient) {
        this.restClient = envServiceRestClient;
    }

    /**
     * 列出纳管容器名。
     *
     * @return 容器名;env-service 不可达时为空列表
     */
    public List<String> listServices() {
        try {
            Envelope<List<JsonNode>> envelope = restClient.get()
                    .uri("/api/environment/services")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return List.of();
            }
            return envelope.data().stream()
                    .map(n -> n.path("name").asText(null))
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("env-service list failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 取纳管源的最近日志行(DOCKER 容器名、FILE 路径源或 PROC 纳管进程——
     * 都按名称寻址)。
     *
     * @param service 纳管源名称
     * @param limit   最大行数
     * @return 面向 LLM 的渲染:表头 + 行,或一行错误
     */
    public String readLogs(String service, int limit) {
        try {
            int safeLimit = Math.max(1, Math.min(limit, 100));
            String sourceId = findSourceId(service);
            if (sourceId == null) {
                return "ERROR: unknown service " + service + ". Available: " + listServices();
            }
            // 非流式按源 id tail 端点:立即返回 tail 行(流式 /sources/{id}/logs/stream
            // 会保持连接数分钟,工具路径不能调)
            Envelope<List<String>> envelope = restClient.get()
                    .uri("/api/environment/sources/{id}/logs?tail={n}", sourceId, safeLimit)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(no recent logs)";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < envelope.data().size(); i++) {
                sb.append(envelope.data().get(i)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("log tail failed for {}: {}", service, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 纳管源名称 → 源 id(GET /services 返回 id+name 对)。
     *
     * @return id 字符串;名称未知时 null
     */
    private String findSourceId(String name) {
        try {
            Envelope<List<JsonNode>> envelope = restClient.get()
                    .uri("/api/environment/services")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return null;
            }
            return envelope.data().stream()
                    .filter(n -> name.equals(n.path("name").asText(null)))
                    .map(n -> n.path("id").asText(null))
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("env-service lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /** ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }
}
