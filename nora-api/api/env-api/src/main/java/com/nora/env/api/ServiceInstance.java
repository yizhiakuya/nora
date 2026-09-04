package com.nora.env.api;

import java.util.List;

/**
 * 受管服务实例（Docker 容器视角）。
 *
 * @param id     容器/实例 ID
 * @param name   服务名（如 nora-agent、nora-rag）
 * @param image  镜像（如 nora/agent-service:latest）
 * @param status 运行状态（running / exited / restarting …）
 * @param ports  端口映射（如 8080:8080）
 */
public record ServiceInstance(
        String id,
        String name,
        String image,
        String status,
        List<String> ports
) {
}
