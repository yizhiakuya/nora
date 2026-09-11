package com.nora.agent.controller;

import com.nora.agent.service.GitHubOAuthService;
import com.nora.agent.service.McpServerService;
import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub OAuth 设备码登录({@code /api/mcp/oauth/github/**})——MCP 设置页
 * 「GitHub 登录」按钮的支撑端点:配置 client_id → 发起设备码 → 轮询换 token
 * → 自动创建/更新官方 GitHub MCP 服务器。
 *
 * <p>设备码流程无需回调地址,任何部署环境(含内网)都可用;client_id 非机密
 * (出现在授权 URL 中),一次配置所有环境复用。
 */
@RestController
@RequestMapping("/api/mcp/oauth/github")
public class McpOAuthController {

    private final GitHubOAuthService gitHubOAuthService;
    private final McpServerService mcpServerService;

    public McpOAuthController(GitHubOAuthService gitHubOAuthService, McpServerService mcpServerService) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.mcpServerService = mcpServerService;
    }

    /** 登录能力状态:client_id 是否已配置 + 当前 github 服务器概况(驱动前端按钮态)。 */
    @GetMapping("/status")
    public ApiResponse<StatusView> status() {
        boolean configured = !gitHubOAuthService.clientId().isBlank();
        McpServerService.ServerView server = mcpServerService.findByName("github");
        return ApiResponse.ok(new StatusView(
                configured,
                server != null ? server.name() : null,
                server != null ? server.transport() : null,
                server != null ? server.status() : null,
                server != null ? server.toolCount() : null));
    }

    /** 保存 OAuth App 的 client_id(创建 App 时勾选 Enable Device Flow)。 */
    @PutMapping("/client-id")
    public ApiResponse<StatusView> saveClientId(@RequestBody ClientIdRequest request) {
        try {
            gitHubOAuthService.saveClientId(request.clientId());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
        return status();
    }

    /** 清除已保存的 client_id(回到未配置态,便于更换 OAuth App)。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/client-id")
    public ApiResponse<StatusView> clearClientId() {
        gitHubOAuthService.clearClientId();
        return status();
    }

    /** 发起设备码授权:返回 user_code 与验证页地址,前端展示给用户。 */
    @PostMapping("/start")
    public ApiResponse<GitHubOAuthService.StartResult> start() {
        try {
            return ApiResponse.ok(gitHubOAuthService.start());
        } catch (IllegalStateException e) {
            // 未配置 client_id / GitHub 不可达:400 带可操作文案
            throw new BusinessException(400, e.getMessage());
        }
    }

    /** 轮询授权结果;complete 时服务端已自动配置好 github 服务器。 */
    @PostMapping("/poll")
    public ApiResponse<GitHubOAuthService.PollResult> poll(@RequestBody PollRequest request) {
        return ApiResponse.ok(gitHubOAuthService.poll(request.flowId()));
    }

    public record ClientIdRequest(String clientId) {
    }

    public record PollRequest(String flowId) {
    }

    public record StatusView(boolean clientIdConfigured, String serverName, String serverTransport,
                             String serverStatus, Integer toolCount) {
    }
}
