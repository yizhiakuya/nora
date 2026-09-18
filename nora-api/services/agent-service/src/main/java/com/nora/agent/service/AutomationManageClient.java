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
 * 管理 automation-service 的自动任务规则,供 agent 的 {@code manage_automation}
 * 工具:list / create / toggle / remove / run / executions。
 *
 * <p>create 的 prompt 是「未来无人值守轮次的指令」——执行面与 run_command 同级,
 * 审批卡须完整展示(见 ToolStepEmitter.buildApprovalRequest)。
 */
@Service
public class AutomationManageClient {

    private static final Logger log = LoggerFactory.getLogger(AutomationManageClient.class);

    private final RestClient restClient;

    public AutomationManageClient(RestClient automationServiceRestClient) {
        this.restClient = automationServiceRestClient;
    }

    /**
     * 创建规则。
     *
     * @param name        规则名(展示用)
     * @param triggerType manual / daily / weekly
     * @param prompt      自然语言指令(agent 动作;由无人值守通道执行)
     * @return 面向 LLM 的创建结果
     */
    public String create(String name, String triggerType, String prompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("triggerType", triggerType == null || triggerType.isBlank() ? "manual" : triggerType);
            body.put("actionType", "agent");
            body.put("prompt", prompt);
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/automations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode d = envelope.data();
            return "已创建自动任务 id=" + d.path("id").asLong()
                    + " name=" + d.path("name").asText(name)
                    + " 触发=" + d.path("triggerLabel").asText(d.path("triggerType").asText("?"))
                    + " enabled=" + d.path("enabled").asBoolean(true)
                    + "。执行历史可在自动任务页查看";
        } catch (Exception e) {
            log.warn("automation create failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 启用/暂停(取反)。 */
    public String toggle(long id) {
        try {
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/automations/{id}/toggle", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode d = envelope.data();
            return "已" + (d.path("enabled").asBoolean() ? "启用" : "暂停") + "自动任务 id=" + id
                    + "(" + d.path("name").asText("?") + ")";
        } catch (Exception e) {
            log.warn("automation toggle failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 删除规则(执行历史保留)。 */
    public String remove(long id) {
        try {
            Envelope<Void> envelope = restClient.delete()
                    .uri("/api/automations/{id}", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            return "已删除自动任务 id=" + id + "(历史执行记录保留)";
        } catch (Exception e) {
            log.warn("automation remove failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 立即运行一次(执行可能跑数分钟,期间同规则不并发)。 */
    public String run(long id) {
        try {
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/automations/{id}/run", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            JsonNode d = envelope.data();
            String status = d.path("status").asText("?");
            String detail = d.path("detail").asText("");
            if (detail.length() > 3000) {
                detail = detail.substring(0, 3000) + "…(截断)";
            }
            return "已运行自动任务 id=" + id + " status=" + status
                    + "\n执行详情:\n" + detail;
        } catch (Exception e) {
            log.warn("automation run failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** 列出规则(最新在前)。 */
    public String list() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/automations")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(尚无自动任务)";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                sb.append("id=").append(n.path("id").asLong())
                        .append(" | ").append(n.path("name").asText("?"))
                        .append(" | ").append(n.path("triggerLabel").asText(n.path("triggerType").asText("?")))
                        .append(" | ").append(n.path("enabled").asBoolean() ? "enabled" : "paused")
                        .append(" | ").append(n.path("status").asText("?"))
                        .append(" | lastRun=").append(n.path("lastRunAt").isNull() ? "never" : n.path("lastRunAt").asText())
                        .append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("automation list failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 执行历史(跨规则,最新在前)。
     *
     * @param limit 条数(1-200,默认 20)
     */
    public String executions(int limit) {
        try {
            int safeLimit = Math.max(1, Math.min(limit, 200));
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/automations/executions?limit={n}", safeLimit)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null || !envelope.data().isArray()) {
                return "ERROR: " + (envelope == null ? "empty response" : envelope.message());
            }
            if (envelope.data().isEmpty()) {
                return "(尚无执行记录)";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : envelope.data()) {
                String detail = n.path("detail").asText("");
                if (detail.length() > 200) {
                    detail = detail.substring(0, 200) + "…";
                }
                sb.append(n.path("startedAt").asText("?"))
                        .append(" | ").append(n.path("ruleName").asText("?"))
                        .append(" | ").append(n.path("status").asText("?"))
                        .append(" | ").append(n.path("durationMs").isNull() ? "-" : n.path("durationMs").asLong() + "ms")
                        .append(" | ").append(detail.replace('\n', ' '))
                        .append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("automation executions failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /** ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }
}
