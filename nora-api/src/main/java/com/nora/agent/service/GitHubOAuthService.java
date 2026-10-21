package com.nora.agent.service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.common.http.ProxySettingsHolder;

/**
 * GitHub OAuth 设备码流程(与 gh CLI 同款):用户在 GitHub 页面输入验证码
 * 完成授权,Nora 轮询换取 access token 并自动配置官方 GitHub MCP 服务器。
 *
 * <p>为什么是设备码而不是浏览器回调:无需公网回调地址/本地监听端口,
 * 部署在任何环境(含内网)都能用;代价是用户在浏览器多输一次验证码。
 *
 * <p>GitHub 不支持动态客户端注册(RFC 7591),client_id 需一次性在
 * github.com/settings/developers 创建 OAuth App(勾选 Enable Device Flow)后
 * 配置——device flow 对回调地址无要求,同一个 client_id 可复用于所有部署。
 * client_id 非机密(会出现在授权 URL 中),存 app_setting,静态配置
 * {@code nora.github.oauth.client-id} 仅作兜底。
 *
 * <p>token 安全:只在内存与本服务的 HTTP 调用中使用,落库进 mcp_server.headers
 * (连接必需);API 回读走既有 maskValues 脱敏;日志绝不打印 token。
 */
@Service
public class GitHubOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthService.class);

    /** 官方 GitHub MCP 远程端点(44 工具,含 get_me;STREAMABLE)。 */
    static final String OFFICIAL_MCP_URL = "https://api.githubcopilot.com/mcp/";
    static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    /** 覆盖官方 MCP 声明的常用能力(repos/issues/PRs/orgs/user/gists)。 */
    static final String DEFAULT_SCOPES = "repo read:org read:user user:email gist";
    static final String SETTING_KEY = "github_oauth";
    /** 设备码有效期 15 分钟(GitHub 固定 expires_in=900,此处兜底)。 */
    private static final long FLOW_TTL_MS = 15 * 60 * 1000L;

    /** HTTP 调用的可测缝:测试注入假实现,生产走 JDK HttpClient + 出站代理。 */
    interface HttpPoster {
        String postForm(String url, String formBody) throws Exception;
    }

    private final ObjectMapper objectMapper;
    private final AppSettingStore appSettingStore;
    private final McpServerService mcpServerService;
    private final String staticClientId;
    private final HttpPoster httpPoster;
    /** flowId → 进行中的设备码授权(内存态:服务重启后需重新发起,可接受)。 */
    private final Map<String, PendingFlow> pending = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubOAuthService(ObjectMapper objectMapper,
                              AppSettingStore appSettingStore,
                              McpServerService mcpServerService,
                              @Value("${nora.github.oauth.client-id:}") String staticClientId) {
        this(objectMapper, appSettingStore, mcpServerService, staticClientId, null);
    }

    GitHubOAuthService(ObjectMapper objectMapper, AppSettingStore appSettingStore,
                       McpServerService mcpServerService, String staticClientId, HttpPoster httpPoster) {
        this.objectMapper = objectMapper;
        this.appSettingStore = appSettingStore;
        this.mcpServerService = mcpServerService;
        this.staticClientId = staticClientId == null ? "" : staticClientId.trim();
        this.httpPoster = httpPoster != null ? httpPoster : this::postFormJdk;
    }

    // ---------- client_id 配置 ----------

    /** 当前生效的 client_id:app_setting 覆盖 > 静态配置兜底;未配置返回空串。 */
    public String clientId() {
        Map<String, Object> stored = appSettingStore.raw(SETTING_KEY);
        if (stored != null) {
            Object v = stored.get("clientId");
            if (v instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return staticClientId;
    }

    /** 保存 client_id(OAuth App 创建后粘贴一次,所有环境复用)。 */
    public void saveClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("client_id 不能为空");
        }
        appSettingStore.save(SETTING_KEY, Map.of("clientId", clientId.trim()));
    }

    /** 清除已保存的 client_id(回到未配置态;静态兜底配置仍生效)。 */
    public void clearClientId() {
        appSettingStore.save(SETTING_KEY, Map.of());
    }

    // ---------- 设备码流程 ----------

    /**
     * 发起设备码授权:向 GitHub 申请 device_code + user_code,
     * 返回给前端展示(用户在浏览器输入 user_code 完成授权)。
     */
    public StartResult start() {
        String clientId = clientId();
        if (clientId.isBlank()) {
            throw new IllegalStateException("尚未配置 GitHub OAuth Client ID——请先创建 OAuth App(勾选 Enable Device Flow)并粘贴 Client ID");
        }
        sweepExpired();
        String body;
        try {
            body = httpPoster.postForm(DEVICE_CODE_URL,
                    "client_id=" + enc(clientId) + "&scope=" + enc(DEFAULT_SCOPES));
        } catch (Exception e) {
            throw new IllegalStateException("连接 GitHub 失败: " + shorten(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        JsonNode node = parse(body, "设备码响应");
        String deviceCode = node.path("device_code").asText("");
        String userCode = node.path("user_code").asText("");
        String verificationUri = node.path("verification_uri").asText("https://github.com/login/device");
        if (deviceCode.isBlank() || userCode.isBlank()) {
            // GitHub 出错时返回 {"error": ..., "error_description": ...}
            String err = node.path("error_description").asText(node.path("error").asText("未知错误"));
            throw new IllegalStateException("GitHub 拒绝设备码请求: " + shorten(err));
        }
        int interval = Math.max(5, node.path("interval").asInt(5));
        long expiresIn = node.path("expires_in").asLong(900);
        String flowId = UUID.randomUUID().toString();
        pending.put(flowId, new PendingFlow(deviceCode, clientId, interval,
                System.currentTimeMillis() + Math.min(expiresIn * 1000L, FLOW_TTL_MS)));
        log.info("github oauth: device flow started (flowId={}, interval={}s)", flowId, interval);
        return new StartResult(flowId, userCode, verificationUri, expiresIn, interval);
    }

    /**
     * 轮询授权结果:待授权=继续等;授权完成=换 token 并自动配置 github MCP
     * (有则更新凭据+启用,无则创建),然后测试连接拉工具清单。
     */
    public PollResult poll(String flowId) {
        PendingFlow flow = flowId == null ? null : pending.get(flowId);
        if (flow == null) {
            return new PollResult("expired", "授权会话不存在或已过期,请重新发起", null, null, null);
        }
        if (System.currentTimeMillis() > flow.expiresAt()) {
            pending.remove(flowId);
            return new PollResult("expired", "验证码已过期,请重新发起授权", null, null, null);
        }
        String body;
        try {
            body = httpPoster.postForm(TOKEN_URL,
                    "client_id=" + enc(flow.clientId())
                            + "&device_code=" + enc(flow.deviceCode())
                            + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
        } catch (Exception e) {
            // 网络抖动:保留 flow 让前端继续轮询,不误报失败
            return new PollResult("pending", "连接 GitHub 失败,重试中…", null, null, null);
        }
        JsonNode node = parse(body, "token 响应");
        String token = node.path("access_token").asText("");
        if (token.isBlank()) {
            String error = node.path("error").asText("");
            return switch (error) {
                case "authorization_pending" -> new PollResult("pending", null, null, null, null);
                case "slow_down" -> new PollResult("slow_down", "轮询过快,已自动降速", null, null, null);
                case "access_denied" -> {
                    pending.remove(flowId);
                    yield new PollResult("denied", "你在 GitHub 页面拒绝了授权", null, null, null);
                }
                case "expired_token" -> {
                    pending.remove(flowId);
                    yield new PollResult("expired", "验证码已过期,请重新发起授权", null, null, null);
                }
                default -> new PollResult("error",
                        "GitHub 返回错误: " + shorten(node.path("error_description").asText(error.isBlank() ? "未知" : error)),
                        null, null, null);
            };
        }
        pending.remove(flowId);
        return completeLogin(token);
    }

    /** 拿到 token:创建或更新名为 github 的服务器为官方远程端点,并测试连接。 */
    private PollResult completeLogin(String token) {
        Map<String, String> headers = Map.of("Authorization", "Bearer " + token);
        try {
            McpServerService.ServerView existing = mcpServerService.findByName("github");
            long id;
            if (existing == null) {
                id = mcpServerService.create("github", OFFICIAL_MCP_URL, "STREAMABLE", headers,
                        null, null, null).id();
            } else {
                if ("STDIO".equals(existing.transport())) {
                    return new PollResult("error",
                            "已存在本地进程版「github」服务器,无法用 OAuth 覆盖——请先删除或改名该服务器,再重试登录",
                            null, null, null);
                }
                McpServerService.ServerView updated =
                        mcpServerService.updateRemoteCredentials(existing.id(), OFFICIAL_MCP_URL, headers);
                if (updated == null) {
                    return new PollResult("error", "更新 github 服务器失败(可能已被删除),请重试", null, null, null);
                }
                mcpServerService.setEnabled(existing.id(), true);
                id = existing.id();
            }
            String warning = null;
            int toolCount = 0;
            try {
                toolCount = mcpServerService.refresh(id).size();
            } catch (Exception e) {
                // token 已保存;连接失败(网络/权限不足)如实提示,用户可稍后测试连接重试
                warning = "已保存凭据,但连接测试失败: " + shorten(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            log.info("github oauth: login complete (server=github, tools={}, warning={})", toolCount, warning != null);
            return new PollResult("complete", null, "github", toolCount, warning);
        } catch (IllegalArgumentException e) {
            return new PollResult("error", shorten(e.getMessage()), null, null, null);
        }
    }

    // ---------- 内部实现 ----------

    /** 默认 HTTP 实现:JDK HttpClient,遵循出站代理设置(与 LLM 调用同一套)。 */
    private String postFormJdk(String url, String formBody) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        InetSocketAddress proxy = ProxySettingsHolder.addressFor(url);
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = builder.build().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500) {
            throw new IllegalStateException("GitHub 服务异常(HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    private JsonNode parse(String body, String what) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("解析 GitHub " + what + "失败");
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String shorten(String message) {
        return message.length() <= 200 ? message : message.substring(0, 200) + "…";
    }

    /** 清掉过期的进行中授权(每次 start 顺带清扫)。 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    // ---------- 记录类型 ----------

    private record PendingFlow(String deviceCode, String clientId, int interval, long expiresAt) {
    }

    /** POST /api/mcp/oauth/github/start 响应。 */
    public record StartResult(String flowId, String userCode, String verificationUri,
                              long expiresIn, int interval) {
    }

    /** POST /api/mcp/oauth/github/poll 响应;status: pending/slow_down/complete/expired/denied/error。 */
    public record PollResult(String status, String message, String serverName,
                             Integer toolCount, String warning) {
    }
}
