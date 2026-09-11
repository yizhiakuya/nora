package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GitHub OAuth 设备码流程:start(申请设备码)→ poll(轮询换 token →
 * 自动配置 github 服务器)。HTTP 走可注入的 HttpPoster 测试缝。
 */
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
        IllegalStateException e = assertThrows(IllegalStateException.class, svc::start);
        assertTrue(e.getMessage().contains("Client ID"), "未配置 client_id 时给出可操作提示");
    }

    @Test
    void startReturnsDeviceCodePayload() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> {
            assertTrue(url.contains("device/code"), "start 调 device/code 端点");
            assertTrue(body.contains("client_id=Ov23test"), "请求带 client_id");
            return "{\"device_code\":\"dc-123\",\"user_code\":\"ABCD-1234\","
                    + "\"verification_uri\":\"https://github.com/login/device\","
                    + "\"expires_in\":900,\"interval\":5}";
        });
        GitHubOAuthService.StartResult r = svc.start();
        assertEquals("ABCD-1234", r.userCode());
        assertEquals("https://github.com/login/device", r.verificationUri());
        assertEquals(5, r.interval());
        assertNotNull(r.flowId());
    }

    @Test
    void startSurfacesGitHubError() {
        GitHubOAuthService svc = buildWith("bad-client", (url, body) ->
                "{\"error\":\"invalid_client\",\"error_description\":\"The client_id is not valid\"}");
        IllegalStateException e = assertThrows(IllegalStateException.class, svc::start);
        assertTrue(e.getMessage().contains("client_id is not valid"), "GitHub 错误透出");
    }

    @Test
    void pollPendingKeepsFlowAlive() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> url.contains("device/code")
                ? "{\"device_code\":\"dc-1\",\"user_code\":\"AAAA-1111\",\"expires_in\":900,\"interval\":5}"
                : "{\"error\":\"authorization_pending\"}");
        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        assertEquals("pending", r.status(), "未授权时继续等待");
        // flow 仍在:再 poll 一次仍是 pending(不被误清)
        assertEquals("pending", svc.poll(flowId).status());
    }

    @Test
    void pollExpiredTokenClearsFlow() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> url.contains("device/code")
                ? "{\"device_code\":\"dc-2\",\"user_code\":\"BBBB-2222\",\"expires_in\":900,\"interval\":5}"
                : "{\"error\":\"expired_token\"}");
        String flowId = svc.start().flowId();
        assertEquals("expired", svc.poll(flowId).status());
        // flow 已清:再次 poll 报会话不存在
        assertEquals("expired", svc.poll(flowId).status());
        assertTrue(svc.poll(flowId).message().contains("重新发起"));
    }

    @Test
    void pollDeniedByUser() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> url.contains("device/code")
                ? "{\"device_code\":\"dc-3\",\"user_code\":\"CCCC-3333\",\"expires_in\":900,\"interval\":5}"
                : "{\"error\":\"access_denied\"}");
        String flowId = svc.start().flowId();
        GitHubOAuthService.PollResult r = svc.poll(flowId);
        assertEquals("denied", r.status());
        assertTrue(r.message().contains("拒绝"));
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
        assertEquals("complete", r.status());
        assertEquals("github", r.serverName());
        assertEquals(2, r.toolCount(), "工具数来自 refresh");
        assertEquals(null, r.warning());
        // 官方端点 + Bearer 头真正落库
        Mockito.verify(mcp).create("github", GitHubOAuthService.OFFICIAL_MCP_URL, "STREAMABLE",
                Map.of("Authorization", "Bearer gho_test123"), null, null, null);
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
        assertEquals("complete", r.status(), "已有服务器走更新而非创建");
        Mockito.verify(mcp).setEnabled(7L, true);
        Mockito.verify(mcp).updateRemoteCredentials(7L, GitHubOAuthService.OFFICIAL_MCP_URL,
                Map.of("Authorization", "Bearer gho_new"));
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
        assertEquals("complete", r.status());
        assertNotNull(r.warning());
        assertTrue(r.warning().contains("network unreachable"));
    }

    @Test
    void pollUnknownFlowId() {
        GitHubOAuthService svc = buildWith("Ov23test", (url, body) -> "{}");
        GitHubOAuthService.PollResult r = svc.poll("no-such-flow");
        assertEquals("expired", r.status());
        assertTrue(r.message().contains("重新发起"));
    }

    @Test
    void saveClientIdRejectsBlank() {
        AppSettingStore store = mock(AppSettingStore.class);
        when(store.raw(anyString())).thenReturn(null);
        GitHubOAuthService svc = new GitHubOAuthService(objectMapper, store,
                mock(McpServerService.class), "", (url, body) -> "{}");
        assertThrows(IllegalArgumentException.class, () -> svc.saveClientId("  "));
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
        assertEquals("Ov23stored", svc.clientId(), "存储覆盖优先");
        svc.clearClientId();
        Mockito.verify(store).save(GitHubOAuthService.SETTING_KEY, Map.of());
    }
}
