package com.nora.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.service.McpServerService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;

/**
 * 设置中心的 MCP 服务器注册管理 API({@code /api/mcp/servers}):
 * 注册远程工具服务器、测试连通(refresh 拉 tools/list 进缓存)、
 * 启用/停用、删除。响应用 ApiResponse 信封;所有视图中头值均已脱敏。
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private final McpServerService mcpServerService;

    public McpServerController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    /** 列出全部注册服务器(headers 脱敏)。 */
    @GetMapping
    public ApiResponse<List<McpServerService.ServerView>> list() {
        return ApiResponse.ok(mcpServerService.list());
    }

    /** 注册服务器。此处不尝试连接——由 refresh 完成。 */
    @PostMapping
    public ApiResponse<McpServerService.ServerView> create(@RequestBody CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        boolean stdio = request.transport() != null && "STDIO".equalsIgnoreCase(request.transport().trim());
        if (stdio) {
            if (request.command() == null || request.command().isBlank()) {
                throw new BusinessException(400, "command is required for STDIO transport");
            }
        } else if (request.url() == null || request.url().isBlank()) {
            throw new BusinessException(400, "url is required");
        }
        try {
            return ApiResponse.ok(mcpServerService.create(
                    request.name(), request.url(), request.transport(), request.headers(),
                    request.command(), request.args(), request.env()));
        } catch (IllegalArgumentException e) {
            // 命令预检失败等:400 带可操作文案(缺 Node.js 时前端直接展示)
            throw new BusinessException(400, e.getMessage());
        }
    }

    /** 删除服务器(其池化 MCP 客户端被关闭)。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!mcpServerService.delete(id)) {
            throw new BusinessException(404, "mcp server not found: " + id);
        }
        return ApiResponse.ok();
    }

    /** 启用/停用服务器;停用服务器的工具不挂载。 */
    @PutMapping("/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        if (request.enabled() == null) {
            throw new BusinessException(400, "enabled is required");
        }
        if (!mcpServerService.setEnabled(id, request.enabled())) {
            throw new BusinessException(404, "mcp server not found: " + id);
        }
        return ApiResponse.ok();
    }

    /**
     * 设置工具加载策略(2026-09-18 P2-9):
     * eager=工具直接挂载(每轮注入 tools spec);lazy=按需(不挂载,
     * agent 经 manage_mcp action=tools/call 使用,省固定上下文成本)。
     */
    @PutMapping("/{id}/tool-policy")
    public ApiResponse<Void> setToolPolicy(@PathVariable long id, @RequestBody ToolPolicyRequest request) {
        try {
            if (!mcpServerService.setToolPolicy(id, request.toolPolicy())) {
                throw new BusinessException(404, "mcp server not found: " + id);
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
        return ApiResponse.ok();
    }

    /**
     * 连接(initialize)并拉 tools/list,把工具清单快照进 tools_cache
     * ——连通性测试与挂载数据源二合一。
     */
    @PostMapping("/{id}/refresh")
    public ApiResponse<RefreshResult> refresh(@PathVariable long id) {
        try {
            List<McpServerService.ToolEntry> tools = mcpServerService.refresh(id);
            return ApiResponse.ok(new RefreshResult("connected", null,
                    tools.stream().map(t -> new ToolInfo(t.name(), t.description())).toList()));
        } catch (IllegalStateException e) {
            // refresh 已记录 status=error + detail;以 502 语义的业务错误上抛
            throw new BusinessException(502, e.getMessage());
        }
    }

    /**
     * 读取某服务器的工具缓存快照(name/description/inputSchema),供管理 UI
     * 的工具列表与详情——绝不触发远端调用;缓存由 refresh/连通测试填充。
     */
    @GetMapping("/{id}/tools")
    public ApiResponse<ToolDetailResult> tools(@PathVariable long id) {
        List<McpServerService.ToolEntry> tools = mcpServerService.cachedTools(id);
        if (tools == null) {
            throw new BusinessException(404, "mcp server not found: " + id);
        }
        return ApiResponse.ok(new ToolDetailResult(tools.stream()
                .map(t -> new ToolDetail(t.name(), t.description(), t.inputSchema()))
                .toList()));
    }

    public record CreateRequest(String name, String url, String transport, Map<String, String> headers,
                                String command, List<String> args, Map<String, String> env) {
    }

    public record EnabledRequest(Boolean enabled) {
    }

    /** PUT /{id}/tool-policy 请求体。 */
    public record ToolPolicyRequest(String toolPolicy) {
    }

    public record ToolInfo(String name, String description) {
    }

    /** Tool entry with full inputSchema for the detail view (JsonNode 序列化为 JSON 对象)。 */
    public record ToolDetail(String name, String description,
                             com.fasterxml.jackson.databind.JsonNode inputSchema) {
    }

    public record ToolDetailResult(List<ToolDetail> tools) {
    }

    public record RefreshResult(String status, String error, List<ToolInfo> tools) {
    }
}
