package com.nora.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound proxy settings for external (non-LAN) HTTP calls.
 *
 * <p>Scope: backend egress only (LLM upstreams, embedding providers, ...).
 * Service-to-service calls inside the LAN never use the proxy —
 * {@link ProxySupport#isLanTarget(String)} bypasses it.
 *
 * <p>Configuration (application.yml or env):
 * <pre>
 * nora.proxy.enabled: true
 * nora.proxy.host: 127.0.0.1
 * nora.proxy.port: 7897
 * </pre>
 */
@ConfigurationProperties(prefix = "nora.proxy")
public record ProxyProperties(boolean enabled, String host, int port) {

    public ProxyProperties {
        if (host == null || host.isBlank()) host = "127.0.0.1";
    }

    /** Defaults for disabled state (yml omits the block entirely). */
    public static ProxyProperties disabled() {
        return new ProxyProperties(false, "127.0.0.1", 0);
    }

    /** A proxy is usable only when enabled with a valid port. */
    public boolean usable() {
        return enabled && port > 0 && port <= 65535;
    }
}
