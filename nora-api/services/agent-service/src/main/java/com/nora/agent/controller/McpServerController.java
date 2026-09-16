package com.nora.agent.controller;

import com.nora.agent.service.McpServerService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP server registry admin API ({@code /api/mcp/servers}) for the settings
 * center: register remote tool servers, test connectivity (refresh pulls
 * tools/list into the cache), enable/disable, delete. Responses use the
 * ApiResponse envelope; header values are masked in every view.
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private final McpServerService mcpServerService;

    public McpServerController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    /** Lists all registered servers (headers masked). */
    @GetMapping
    public ApiResponse<List<McpServerService.ServerView>> list() {
        return ApiResponse.ok(mcpServerService.list());
    }

    /** Registers a server. No connection is attempted here — refresh does that. */
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

    /** Deletes a server (its pooled MCP client is closed). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!mcpServerService.delete(id)) {
            throw new BusinessException(404, "mcp server not found: " + id);
        }
        return ApiResponse.ok();
    }

    /** Enables/disables a server; disabled servers' tools are not mounted. */
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
     * Connects (initialize) and pulls tools/list, snapshotting the tool list
     * into tools_cache — the connectivity test and the mount source in one.
     */
    @PostMapping("/{id}/refresh")
    public ApiResponse<RefreshResult> refresh(@PathVariable long id) {
        try {
            List<McpServerService.ToolEntry> tools = mcpServerService.refresh(id);
            return ApiResponse.ok(new RefreshResult("connected", null,
                    tools.stream().map(t -> new ToolInfo(t.name(), t.description())).toList()));
        } catch (IllegalStateException e) {
            // refresh already recorded status=error + detail; surface as 502-style business error
            throw new BusinessException(502, e.getMessage());
        }
    }

    /**
     * Reads one server's cached tool snapshot (name/description/inputSchema)
     * for the admin UI's tool list & detail view — never triggers a remote
     * call; the cache is filled by refresh/test-connection.
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
