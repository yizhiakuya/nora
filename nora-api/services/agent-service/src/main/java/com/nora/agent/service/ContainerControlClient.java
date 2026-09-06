package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Executes high-risk container operations via env-service
 * ({@code POST /api/environment/services/{id}/start|stop|restart}).
 * Only called after user approval in ASSIST mode (spec 高风险审批协议).
 */
@Service
public class ContainerControlClient {

    private static final Logger log = LoggerFactory.getLogger(ContainerControlClient.class);

    private final RestClient restClient;

    public ContainerControlClient(RestClient envServiceRestClient) {
        this.restClient = envServiceRestClient;
    }

    /**
     * @param action start / stop / restart
     * @return LLM-friendly result line, "ERROR: ..." on failure
     */
    public String control(String container, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase();
        try {
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/environment/services/{id}/{op}", container, normalized)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0) {
                String message = envelope == null ? "empty response" : envelope.message();
                return "ERROR: 容器 " + normalized + " 失败: " + message;
            }
            String status = envelope.data() != null && envelope.data().has("status")
                    ? envelope.data().path("status").asText("") : "";
            return "容器 " + container + " 已执行 " + normalized + ",当前状态: " + status;
        } catch (Exception e) {
            log.warn("container {} failed for {}: {}", normalized, container, e.getMessage());
            return "ERROR: 容器 " + normalized + " 失败: "
                    + (e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
