package com.nora.agent.controller;

import com.nora.common.http.ProxyProperties;
import com.nora.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Network egress settings: surfaces the running proxy configuration and
 * offers a proxied connectivity probe the settings page can use.
 * Read-only by design — proxy config comes from application.yml / env
 * (persistent user settings storage is tracked separately).
 */
@RestController
@RequestMapping("/api/network")
public class NetworkController {

    private final ProxyProperties proxyProperties;

    public NetworkController(ProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }

    /** Current egress proxy state as the JVM sees it. */
    @GetMapping("/proxy")
    public ApiResponse<ProxyView> proxy() {
        boolean usable = proxyProperties.usable();
        return ApiResponse.ok(new ProxyView(
                usable,
                usable ? proxyProperties.host() : null,
                usable ? proxyProperties.port() : null,
                usable ? "http://" + proxyProperties.host() + ":" + proxyProperties.port() : null));
    }

    /**
     * Probes an external URL through the configured proxy (and, for
     * comparison, directly). Lets the UI show whether the proxy actually
     * unlocks a destination before the user commits to it.
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
        long start = System.currentTimeMillis();
        // direct leg
        int directStatus = probe(url, null);
        long directMs = System.currentTimeMillis() - start;
        // proxied leg (only meaningful when a proxy is configured)
        Integer proxyStatus = null;
        Long proxyMs = null;
        if (proxyProperties.usable()) {
            long pStart = System.currentTimeMillis();
            proxyStatus = probe(url, new InetSocketAddress(proxyProperties.host(), proxyProperties.port()));
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

    /** GET /api/network/proxy response. */
    public record ProxyView(boolean enabled, String host, Integer port, String url) {
    }

    /** POST /api/network/probe body. */
    public record ProbeRequest(String url) {
    }

    /** POST /api/network/probe response. status 0 = transport failure. */
    public record ProbeResult(Integer directStatus, Long directMs, Integer proxyStatus, Long proxyMs) {
    }
}
