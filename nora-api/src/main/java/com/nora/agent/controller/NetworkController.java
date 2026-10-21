package com.nora.agent.controller;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nora.agent.service.AppSettingStore;
import com.nora.common.http.ProxyProperties;
import com.nora.common.http.ProxySettingsHolder;
import com.nora.common.response.ApiResponse;

/**
 * 网络出站设置:查看、更新与探测出站代理。
 *
 * <p>代理配置持久化在 {@code app_setting} 表,保存即应用到运行时持有器——
 * 无需重启。启动默认值仍来自 application.yml / env({@link ProxyProperties}
 * 绑定);持久化覆盖值在启动时替换它们。
 */
@RestController
@RequestMapping("/api/network")
public class NetworkController {

    private static final String SETTING_KEY = "proxy";

    private final ProxyProperties staticProxy;
    private final AppSettingStore appSettingStore;

    public NetworkController(ProxyProperties staticProxy, AppSettingStore appSettingStore) {
        this.staticProxy = staticProxy;
        this.appSettingStore = appSettingStore;
        // 启动基线:先按静态配置(application.yml / env)初始化 holder——
        // 无持久化行时它就是最终值(此前 holder 会停在 disabled,与文档不符);
        // 有持久化行时由 AppSettingStore 在 boot 时覆盖(见 onLoad)。
        ProxySettingsHolder.set(staticProxy);
        // 运行时应用器:任何保存(或启动加载)都替换生效中的持有器。
        appSettingStore.onLoad(SETTING_KEY, payload -> {
            ProxyProperties parsed = parse(payload);
            ProxySettingsHolder.set(parsed);
            return null;
        });
    }

    /** 运行中 JVM 视角的当前出站代理状态。 */
    @GetMapping("/proxy")
    public ApiResponse<ProxyView> proxy() {
        ProxyProperties current = ProxySettingsHolder.current();
        boolean usable = current.usable();
        return ApiResponse.ok(new ProxyView(
                usable,
                current.host(),
                usable ? current.port() : null,
                usable ? "http://" + current.host() + ":" + current.port() : null));
    }

    /**
     * 更新并持久化代理。立即生效——在途对话保持既有客户端;
     * 下一次出站调用使用新设置。
     */
    @PutMapping("/proxy")
    public ApiResponse<ProxyView> update(@RequestBody UpdateRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        int port = 0;
        if (enabled) {
            if (request.host() == null || request.host().isBlank()) {
                throw new com.nora.common.exception.BusinessException(400, "启用代理时 host 不能为空");
            }
            if (request.port() == null || request.port() <= 0 || request.port() > 65535) {
                throw new com.nora.common.exception.BusinessException(400, "启用代理时 port 必须为 1-65535");
            }
            port = request.port();
        }
        ProxyProperties next = new ProxyProperties(enabled, request.host() == null ? "" : request.host().trim(), port,
                staticProxy.bypassHosts());
        if (enabled) {
            // 代理不可达时快速失败,而不是静默弄断出站。
            String probeUrl = "http://" + next.host() + ":" + next.port();
            InetSocketAddress addr = new InetSocketAddress(next.host(), next.port());
            if (addr.isUnresolved()) {
                throw new com.nora.common.exception.BusinessException(400, "代理主机无法解析: " + next.host());
            }
            if (probe(probeUrl, addr) == 0) {
                throw new com.nora.common.exception.BusinessException(400,
                        "代理不可达(" + probeUrl + ")，请确认地址与端口");
            }
        }
        appSettingStore.save(SETTING_KEY, Map.of(
                "enabled", next.enabled(),
                "host", next.host(),
                "port", next.port()));
        return proxy();
    }

    /** 清除持久化覆盖值,回退静态配置。 */
    @PostMapping("/proxy/reset")
    public ApiResponse<ProxyView> reset() {
        appSettingStore.save(SETTING_KEY, Map.of(
                "enabled", staticProxy.enabled(),
                "host", staticProxy.host(),
                "port", staticProxy.port()));
        return proxy();
    }

    private ProxyProperties parse(Map<String, Object> payload) {
        boolean enabled = Boolean.TRUE.equals(payload.get("enabled"));
        Object hostObj = payload.get("host");
        Object portObj = payload.get("port");
        String host = hostObj instanceof String s ? s : "127.0.0.1";
        int port;
        try {
            port = portObj instanceof Number n ? n.intValue() : Integer.parseInt(portObj.toString());
        } catch (Exception e) {
            port = 0;
        }
        // bypass-hosts 属于静态配置(application.yml),不随 UI 保存变化
        return new ProxyProperties(enabled, host, port, staticProxy.bypassHosts());
    }

    /**
     * 经当前代理(并对照直连)探测外部 URL。让 UI 在用户确认前展示
     * 代理是否真能打通某目标。
     */
    @PostMapping("/probe")
    public ApiResponse<ProbeResult> probe(@org.springframework.web.bind.annotation.RequestBody ProbeRequest request) {
        String url = request.url();
        if (url == null || url.isBlank()) {
            throw new com.nora.common.exception.BusinessException(400, "url is required");
        }
        if (!url.matches("(?i)^https?://.*")) {
            throw new com.nora.common.exception.BusinessException(400, "url must start with http(s)://");
        }
        ProxyProperties current = ProxySettingsHolder.current();
        long start = System.currentTimeMillis();
        // 直连段
        int directStatus = probe(url, null);
        long directMs = System.currentTimeMillis() - start;
        // 代理段(仅配置了代理时有意义)
        Integer proxyStatus = null;
        Long proxyMs = null;
        if (current.usable()) {
            long pStart = System.currentTimeMillis();
            proxyStatus = probe(url, new InetSocketAddress(current.host(), current.port()));
            proxyMs = System.currentTimeMillis() - pStart;
        }
        return ApiResponse.ok(new ProbeResult(directStatus, directMs, proxyStatus, proxyMs));
    }

    private int probe(String url, InetSocketAddress proxy) {
        try {
            java.net.http.HttpClient.Builder b = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8));
            if (proxy != null) {
                b.proxy(java.net.ProxySelector.of(proxy));
            }
            java.net.http.HttpResponse<Void> resp = b.build().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        } catch (Exception e) {
            return 0; // transport-level failure (timeout / refused / DNS)
        }
    }

    /** GET/PUT /api/network/proxy 响应。 */
    public record ProxyView(boolean enabled, String host, Integer port, String url) {
    }

    /** PUT /api/network/proxy 请求体。 */
    public record UpdateRequest(Boolean enabled, String host, Integer port) {
    }

    /** POST /api/network/probe 请求体。 */
    public record ProbeRequest(String url) {
    }

    /** POST /api/network/probe 响应。status 0 = 传输层失败。 */
    public record ProbeResult(Integer directStatus, Long directMs, Integer proxyStatus, Long proxyMs) {
    }
}
