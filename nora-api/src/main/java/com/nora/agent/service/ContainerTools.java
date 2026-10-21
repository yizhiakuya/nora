package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/**
 * 通过环境领域服务执行容器操作；调用前由统一工具权限门处理风险与审批。
 */
@Service
public class ContainerTools {

    private static final Logger log = LoggerFactory.getLogger(ContainerTools.class);

    private final com.nora.env.service.EnvironmentService environment;
    private final com.nora.env.service.ManagedSourceService managed;

    public ContainerTools(com.nora.env.service.EnvironmentService environment,
                          com.nora.env.service.ManagedSourceService managed) {
        this.environment = environment;
        this.managed = managed;
    }

    /**
     * @param action 动作:start / stop / restart
     * @return 面向 LLM 的结果行,失败时 "ERROR: ..."
     */
    public ChatToolExecutor.ToolOutcome control(
            String container,
            Action action) {
        if (container == null || container.isBlank() || action == null) {
            return ChatToolExecutor.textOutcome("ERROR: 缺少 service 或 action 参数，请先查询纳管源名称", null);
        }
        if (managed.list().stream().noneMatch(row -> row.enabled() && container.equals(row.name())
                && ("DOCKER".equals(row.kind()) || "PROC".equals(row.kind())))) {
            return ChatToolExecutor.textOutcome("ERROR: unknown service " + container + ". 可用服务必须来自环境服务注册表", null);
        }
        String normalized = action.name();
        try {
            var result = environment.control(container, normalized);
            if ("error".equals(result.status())) return unknown(normalized, result.detail());
            String content = "容器 " + container + " 已执行 " + normalized + ",当前状态: " + result.status();
            return new ChatToolExecutor.ToolOutcome(content, content, null, false);
        } catch (Exception e) {
            log.warn("container {} failed for {}: {}", normalized, container, e.getMessage());
            return unknown(normalized, e.getMessage());
        }
    }

    public enum Action { start, stop, restart }

    private static ChatToolExecutor.ToolOutcome unknown(String action, String detail) {
        return new ChatToolExecutor.ToolOutcome("ERROR: 容器 " + action + " 结果未知: " + detail
                + "。请求可能已生效；先查询容器实际状态，不能直接重发。", null, null, false, java.util.List.of(), true);
    }
}
