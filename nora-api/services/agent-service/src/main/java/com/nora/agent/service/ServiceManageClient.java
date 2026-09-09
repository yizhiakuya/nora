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
 * Manages env-service managed sources (FILE/DOCKER/PROC registry) for the
 * agent's {@code manage_service} tool: register / enable / disable / remove /
 * list via {@code POST /api/environment/managed} etc.
 *
 * <p>register 对 PROC 源意味着"系统将跟踪一条宿主机命令",注册本身经 CRITICAL
 * 审批;本 client 只转发,校验在 orchestrator(RiskClassifier)与 env-service 双层。
 */
@Service
public class ServiceManageClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceManageClient.class);

    private final RestClient restClient;

    public ServiceManageClient(RestClient envServiceRestClient) {
        this.restClient = envServiceRestClient;
    }

    /**
     * Registers a managed source.
     *
     * @param kind          FILE / DOCKER / PROC
     * @param name          unique registry name
     * @param fileLogPath   FILE kind only
     * @param containerName DOCKER kind only
     * @param command       PROC kind only (startup command)
     * @param workDir       PROC kind only, optional
     * @return LLM-friendly rendering with the created id
     */
    public String register(String kind, String name, String fileLogPath,
                           String containerName, String command, String workDir) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("kind", kind.trim().toUpperCase());
            body.put("name", name);
            if (fileLogPath != null) body.put("fileLogPath", fileLogPath);
            if (containerName != null) body.put("containerName", containerName);
            if (command != null) body.put("command", command);
            if (workDir != null) body.put("workDir", workDir);
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/environment/managed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            long id = envelope.data().path("id").asLong(-1);
            return "已注册纳管源 id=" + id + " name=" + envelope.data().path("name").asText(name)
                    + " kind=" + envelope.data().path("kind").asText(kind)
                    + "。现在可以用 read_service_logs 读取它的日志";
        } catch (Exception e) {
            log.warn("service register failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** Pauses (enabled=false) or resumes (enabled=true) a managed source. */
    public String setEnabled(long id, boolean enabled) {
        try {
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/environment/managed/{id}/enabled", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", enabled))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            return (envelope.data() != null && envelope.data().asBoolean())
                    ? "已" + (enabled ? "恢复" : "暂停") + "纳管源 id=" + id
                    : "ERROR: 操作未生效(env-service 返回 false)";
        } catch (Exception e) {
            log.warn("service toggle failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** Removes a managed source (does not touch the container/file itself). */
    public String remove(long id) {
        try {
            Envelope<JsonNode> envelope = restClient.delete()
                    .uri("/api/environment/managed/{id}", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            return "已删除纳管源 id=" + id + "(容器/文件本身不受影响)";
        } catch (Exception e) {
            log.warn("service remove failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Lists managed sources including paused ones.
     *
     * @return "id | kind | name | detail | enabled" lines
     */
    public String list() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/environment/managed")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(尚无纳管源)";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                String detail = switch (n.path("kind").asText()) {
                    case "FILE" -> n.path("fileLogPath").asText("-");
                    case "DOCKER" -> n.path("containerName").asText("-");
                    default -> n.path("command").asText("-");
                };
                sb.append("id=").append(n.path("id").asLong())
                        .append(" | ").append(n.path("kind").asText("?"))
                        .append(" | ").append(n.path("name").asText("?"))
                        .append(" | ").append(detail)
                        .append(" | ").append(n.path("enabled").asBoolean() ? "enabled" : "paused")
                        .append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("service list failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
