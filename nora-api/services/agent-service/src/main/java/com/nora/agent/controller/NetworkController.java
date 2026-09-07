package com.nora.agent.controller;

import com.nora.agent.service.AppSettingStore;
import com.nora.common.http.ProxyProperties;
import com.nora.common.http.ProxySettingsHolder;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

/**
 * Network egress settings: view, update and probe the outbound proxy.
 *
 * <p>Proxy config is persisted in the {@code app_setting} table and applied
 * to the runtime holder immediately on save — no restart needed. Boot
 * defaults still come from application.yml / env via {@link ProxyProperties}
 * binding; a persisted override replaces them at startup.
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
        // Runtime applier: any save (or boot load) swaps the live holder.
        appSettingStore.onLoad(SETTING_KEY, payload -> {
            ProxyProperties parsed = parse(payload);
            ProxySettingsHolder.set(parsed);
            return null;
        });
    }

    /** Current egress proxy state as the running JVM sees it. */
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
     * Updates and persists the proxy. Applied immediately — in-flight chats
     * keep their existing client; the next outbound call uses the new settings.
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
        ProxyProperties next = new ProxyProperties(enabled, request.host() == null ? "" : request.host().trim(), port);
        if (enabled) {
            // Fail fast on an unreachable proxy rather than silently breaking egress.
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

    /** Clears the persisted override, falling back to static config. */
    @PostMapping("/proxy/reset")
    public ApiResponse<ProxyView> reset() {
        appSettingStore.save(SETTING_KEY, Map.of(
                "enabled", staticProxy.enabled(),
                "host", staticProxy.host(),
                "port", staticProxy.port()));
        return proxy();
    }

    private static ProxyProperties parse(Map<String, Object> payload) {
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
        return new ProxyProperties(enabled, host, port);
    }

    /**
     * Probes an external URL through the current proxy (and, for comparison,
     * directly). Lets the UI show whether the proxy actually unlocks a
     * destination before the user commits to it.
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
        // direct leg
        int directStatus = probe(url, null);
        long directMs = System.currentTimeMillis() - start;
        // proxied leg (only meaningful when a proxy is configured)
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

    /** GET/PUT /api/network/proxy response. */
    public record ProxyView(boolean enabled, String host, Integer port, String url) {
    }

    /** PUT /api/network/proxy body. */
    public record UpdateRequest(Boolean enabled, String host, Integer port) {
    }

    /** POST /api/network/probe body. */
    public record ProbeRequest(String url) {
    }

    /** POST /api/network/probe response. status 0 = transport failure. */
    public record ProbeResult(Integer directStatus, Long directMs, Integer proxyStatus, Long proxyMs) {
    }
}
