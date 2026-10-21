package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/**
 * 纳管源快照,供 agent 的 {@code manage_service status} 工具。
 * 同时报告观察位置和实际探测证据；日志可用性不代表服务存活。
 */
@Service
public class EnvironmentTools {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentTools.class);

    private final com.nora.env.service.EnvironmentService environment;

    public EnvironmentTools(com.nora.env.service.EnvironmentService environment) {
        this.environment = environment;
    }

    /**
     * 全部启用纳管源的状态摘要(DOCKER 带 CPU/内存,PROC 带 pid,FILE 仅报告日志状态)。
     *
     * @return 面向 LLM 的逐行渲染；探测失败时返回 ERROR 行
     */
    public String statusSummary() {
        try {
            var sources = environment.services();
            if (sources.isEmpty()) return "(尚无启用的纳管源)可在环境控制台注册 FILE/DOCKER/PROC 源，或用 manage_service register";
            int healthy = 0;
            int unhealthy = 0;
            int unknown = 0;
            StringBuilder sb = new StringBuilder();
            for (var n : sources) {
                String health = java.util.Objects.toString(n.get("health"), "?");
                if ("healthy".equals(health)) {
                    healthy++;
                } else if ("unknown".equals(health)) {
                    unknown++;
                } else {
                    unhealthy++;
                }
                sb.append("- ").append(java.util.Objects.toString(n.get("name"), "?"))
                        .append(" | ").append(java.util.Objects.toString(n.get("kind"), "?"))
                        .append(" | ").append(health.equals("healthy") ? "✓ healthy" : "unknown".equals(health) ? "未探测" : "⚠ " + health)
                        .append(" | ").append(java.util.Objects.toString(n.get("status"), "?"));
                String cpu = java.util.Objects.toString(n.get("cpu"), null);
                if (cpu != null && !cpu.isBlank() && !"—".equals(cpu)) {
                    sb.append(" | cpu=").append(cpu);
                }
                String memory = java.util.Objects.toString(n.get("memory"), null);
                if (memory != null && !memory.isBlank() && !"—".equals(memory)) {
                    sb.append(" | mem=").append(memory);
                }
                String uptime = java.util.Objects.toString(n.get("uptime"), null);
                if (uptime != null && !uptime.isBlank() && !"—".equals(uptime)) {
                    sb.append(" | up=").append(uptime);
                }
                String detail = java.util.Objects.toString(n.get("detail"), null);
                if (detail != null && !detail.isBlank()) {
                    sb.append(" | ").append(detail);
                }
                sb.append('\n');
            }
            var observer = (java.util.Map<?, ?>) sources.getFirst().get("observer");
            return "纳管源观察位置: " + java.util.Objects.toString(observer.get("host"), "未报告")
                    + " | " + java.util.Objects.toString(observer.get("platform"), "未报告")
                    + " | 执行域=" + java.util.Objects.toString(observer.get("scope"), "未报告")
                    + "\n环境状态(共 " + sources.size() + " 个启用源,healthy=" + healthy
                    + ",异常=" + unhealthy + ",未探测=" + unknown + "):\n" + sb.toString().stripTrailing()
                    + "\nFILE 仅观察日志，不表示服务存活；远端主机须使用对应连接的实时证据，不比较不同主机的同名服务。";
        } catch (Exception e) {
            log.warn("environment status failed: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

}
