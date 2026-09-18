package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 环境健康快照,供 agent 的 {@code environment_status} 工具:
 * 一次拿到全部纳管源的 kind/status/health/cpu/memory/uptime 摘要。
 *
 * <p>动机(2026-09-18 工具设计分析):此前诊断入口只有逐个 read_service_logs
 * 猜哪个服务有问题;人做诊断先看面板,工具应对齐这个工作流。
 */
@Service
public class EnvironmentStatusClient {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentStatusClient.class);

    private final RestClient restClient;

    public EnvironmentStatusClient(RestClient envServiceRestClient) {
        this.restClient = envServiceRestClient;
    }

    /**
     * 全部启用纳管源的状态摘要(DOCKER 带 CPU/内存,PROC 带 pid,FILE 带日志活跃度)。
     *
     * @return 面向 LLM 的逐行渲染;env-service 不可达时返回 ERROR 行
     */
    public String statusSummary() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/environment/services")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(尚无启用的纳管源)可在环境控制台注册 FILE/DOCKER/PROC 源,或用 manage_service register";
            }
            int healthy = 0;
            int unhealthy = 0;
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                String health = n.path("health").asText("?");
                if ("healthy".equals(health)) {
                    healthy++;
                } else {
                    unhealthy++;
                }
                sb.append("- ").append(n.path("name").asText("?"))
                        .append(" | ").append(n.path("kind").asText("?"))
                        .append(" | ").append(health.equals("healthy") ? "✓ healthy" : "⚠ " + health)
                        .append(" | ").append(n.path("status").asText("?"));
                String cpu = n.path("cpu").asText(null);
                if (cpu != null && !cpu.isBlank() && !"—".equals(cpu)) {
                    sb.append(" | cpu=").append(cpu);
                }
                String memory = n.path("memory").asText(null);
                if (memory != null && !memory.isBlank() && !"—".equals(memory)) {
                    sb.append(" | mem=").append(memory);
                }
                String uptime = n.path("uptime").asText(null);
                if (uptime != null && !uptime.isBlank() && !"—".equals(uptime)) {
                    sb.append(" | up=").append(uptime);
                }
                String detail = n.path("detail").asText(null);
                if (detail != null && !detail.isBlank()) {
                    sb.append(" | ").append(detail);
                }
                sb.append('\n');
            }
            return "环境状态(共 " + envelope.data().size() + " 个启用源,healthy=" + healthy
                    + ",异常=" + unhealthy + "):\n" + sb.toString().stripTrailing()
                    + "\n(异常源用 read_service_logs 读其日志定位问题)";
        } catch (Exception e) {
            log.warn("environment status failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }
}
