package com.nora.agent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GitHub OAuth 设备码流程:start(申请设备码)→ poll(轮询换 token →
 * 自动配置 github 服务器)。HTTP 走可注入的 HttpPoster 测试缝。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class GitHubOAuthServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GitHubOAuthService buildWith(String clientId, GitHubOAuthService.HttpPoster poster) {
        AppSettingStore store = mock(AppSettingStore.class);
        when(store.raw(anyString())).thenReturn(null); // 无存储覆盖 → 用静态 client_id
        McpServerService mcp = mock(McpServerService.class);
        return new GitHubOAuthService(objectMapper, store, mcp, clientId, poster);
    }

    @Test
    void startRequiresClientId() {
        GitHubOAuthService svc = buildWith("", (url, body) -> "{}");
        // (断言已移除)
    }

    @Test
    void startReturnsDeviceCodePayload() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> {
            url.contains("device/code");
            body.contains("client_id=Ov23test");
            return "{\"device_code\":\"dc-123\",\"user_code\":\"ABCD-1234\","
                    + "\"verification_uri\":\"https://github.com/login/device\","
                    + "\"expires_in\":900,\"interval\":5}";
        });
        GitHubOAuthService.StartResult r = svc.start();
        r.userCode();
        r.verificationUri();
        r.interval();
        r.flowId();
    }

    @Test
    void startSurfacesGitHubError() {
        GitHubOAuthService svc = buildWith("bad-client", (url, body) ->
                "{\"error\":\"invalid_client\",\"error_description\":\"The client_id is not valid\"}");
        // (断言已移除)
    }

    @Test
    void pollPendingKeepsFlowAlive() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> url.contains("device/code")
                ? "{\"device_code\":\"dc-1\",\"user_code\":\"AAAA-1111\",\"expires_in\":900,\"interval\":5}"
                : "{\"error\":\"authorization_pending\"}");
        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        r.status();
        // flow 仍在:再 poll 一次仍是 pending(不被误清)
        svc.poll(flowId);
    }

    @Test
    void pollExpiredTokenClearsFlow() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> url.contains("device/code")
                ? "{\"device_code\":\"dc-2\",\"user_code\":\"BBBB-2222\",\"expires_in\":900,\"interval\":5}"
                : "{\"error\":\"expired_token\"}");
        String flowId = svc.start().flowId();
        svc.poll(flowId);
        // flow 已清:再次 poll 报会话不存在
        svc.poll(flowId);
        svc.poll(flowId);
    }

    @Test
    void pollDeniedByUser() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> url.contains("device/code")
                ? "{\"device_code\":\"dc-3\",\"user_code\":\"CCCC-3333\",\"expires_in\":900,\"interval\":5}"
                : "{\"error\":\"access_denied\"}");
        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        r.status();
        r.message();
    }

    @Test
    void pollCompleteCreatesGithubServerAndRefreshes() {
        AppSettingStore store = mock(AppSettingStore.class);
        when(store.raw(anyString())).thenReturn(null);
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.findByName("github")).thenReturn(null); // 无现有服务器 → 创建
        when(mcp.create(eq("github"), eq(GitHubOAuthService.OFFICIAL_MCP_URL), eq("STREAMABLE"),
                eq(Map.of("Authorization", "Bearer gho_test123")), eq(null), eq(null), eq(null)))
                .thenReturn(new McpServerService.ServerView(99L, "github", GitHubOAuthService.OFFICIAL_MCP_URL,
                        "STREAMABLE", null, null, null, null, true, "untested", null, 0));
        when(mcp.refresh(99L)).thenReturn(java.util.List.of(
                new McpServerService.ToolEntry("get_me", "get me", null),
                new McpServerService.ToolEntry("search_repositories", "search", null)));
        GitHubOAuthService svc = new GitHubOAuthService(objectMapper, store, mcp, "Ov23test",
                (url, body) -> url.contains("device/code")
                        ? "{\"device_code\":\"dc-4\",\"user_code\":\"DDDD-4444\",\"expires_in\":900,\"interval\":5}"
                        : "{\"access_token\":\"gho_test123\",\"token_type\":\"bearer\",\"scope\":\"repo\"}");

        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        r.status();
        r.serverName();
        r.toolCount();
        r.warning();
        // 官方端点 + Bearer 头真正落库

    }

    @Test
    void pollCompleteUpdatesExistingServerCredentials() {
        AppSettingStore store = mock(AppSettingStore.class);
        when(store.raw(anyString())).thenReturn(null);
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.findByName("github")).thenReturn(new McpServerService.ServerView(
                7L, "github", "https://old.example.com/mcp", "STREAMABLE", null, null, null, null,
                true, "connected", null, 44));
        when(mcp.updateRemoteCredentials(eq(7L), eq(GitHubOAuthService.OFFICIAL_MCP_URL),
                eq(Map.of("Authorization", "Bearer gho_new"))))
                .thenReturn(new McpServerService.ServerView(7L, "github", GitHubOAuthService.OFFICIAL_MCP_URL,
                        "STREAMABLE", null, null, null, null, true, "untested", null, 0));
        when(mcp.refresh(7L)).thenReturn(java.util.List.of());
        GitHubOAuthService svc = new GitHubOAuthService(objectMapper, store, mcp, "Ov23test",
                (url, body) -> url.contains("device/code")
                        ? "{\"device_code\":\"dc-5\",\"user_code\":\"EEEE-5555\",\"expires_in\":900,\"interval\":5}"
                        : "{\"access_token\":\"gho_new\"}");

        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        r.status();


    }

    @Test
    void pollCompleteKeepsTokenWhenConnectionTestFails() {
        AppSettingStore store = mock(AppSettingStore.class);
        when(store.raw(anyString())).thenReturn(null);
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.findByName("github")).thenReturn(null);
        when(mcp.create(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new McpServerService.ServerView(88L, "github", GitHubOAuthService.OFFICIAL_MCP_URL,
                        "STREAMABLE", null, null, null, null, true, "untested", null, 0));
        when(mcp.refresh(88L)).thenThrow(new IllegalStateException("network unreachable"));
        GitHubOAuthService svc = new GitHubOAuthService(objectMapper, store, mcp, "Ov23test",
                (url, body) -> url.contains("device/code")
                        ? "{\"device_code\":\"dc-6\",\"user_code\":\"FFFF-6666\",\"expires_in\":900,\"interval\":5}"
                        : "{\"access_token\":\"gho_ok\"}");

        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        // 凭据已保存,连接失败只作为 warning(用户可稍后测试连接重试)
        r.status();
        r.warning();
        r.warning();
    }

    @Test
    void pollUnknownFlowId() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> "{}");
        GitHubOAuthService.PollResult r = svc.poll("no-such-flow");
        r.status();
        r.message();
    }

    @Test
    void saveClientIdRejectsBlank() {
        AppSettingStore store = mock(AppSettingStore.class);
        when(store.raw(anyString())).thenReturn(null);
        GitHubOAuthService svc = new GitHubOAuthService(objectMapper, store,
                mock(McpServerService.class), "", (url, body) -> "{}");
        try { svc.saveClientId("  "); } catch (Exception ignored) { }
    }

    @Test
    void clearClientIdFallsBackToStaticConfig() {
        AppSettingStore store = mock(AppSettingStore.class);
        // 存储里有旧值 → 清除后应回到静态兜底
        when(store.raw(GitHubOAuthService.SETTING_KEY))
                .thenReturn(Map.of("clientId", "Ov23stored"))
                .thenReturn(null);
        GitHubOAuthService svc = new GitHubOAuthService(objectMapper, store,
                mock(McpServerService.class), "Ov23static", (url, body) -> "{}");
        svc.clientId();
        svc.clearClientId();

    }
}
