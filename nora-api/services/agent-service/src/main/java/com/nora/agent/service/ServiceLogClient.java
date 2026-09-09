package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Tails container logs from env-service
 * ({@code GET /api/environment/services} + {@code POST .../start|stop|restart}).
 *
 * <p>ACI principle: the payload fed to the LLM is the recent tail, cleaned of
 * blank lines, with a line budget — not an unbounded firehose.
 */
@Service
public class ServiceLogClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceLogClient.class);

    private final RestClient restClient;

    public ServiceLogClient(RestClient envServiceRestClient) {
        this.restClient = envServiceRestClient;
    }

    /**
     * Lists the managed container names.
     *
     * @return container names, or an empty list when env-service is unreachable
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
     * Tails recent log lines of a managed source (DOCKER container name, FILE
     * path source, or PROC managed process — all are addressable by name).
     *
     * @param service managed source name
     * @param limit   max lines
     * @return LLM-friendly rendering: header + lines, or an error line
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
     * Managed source name → source id (GET /services returns id+name pairs).
     *
     * @return id as string, or null when the name is unknown
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

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
