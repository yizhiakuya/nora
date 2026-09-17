package com.nora.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
 * nora.proxy.bypass-hosts: home.rainaki.top   # 域名后缀,命中则直连(不走代理)
 * </pre>
 *
 * <p>bypass-hosts 的用途:某些「公网域名」实际指向自己的内网/家庭宽带
 * (如媒体中继 home.rainaki.top)。经代理(远程节点)绕行反而慢且费代理流量,
 * 配到这里后按直连处理——等价于把域名加入「不走代理」名单。
 */
@ConfigurationProperties(prefix = "nora.proxy")
public record ProxyProperties(boolean enabled, String host, int port, List<String> bypassHosts) {

    public ProxyProperties {
        if (host == null || host.isBlank()) host = "127.0.0.1";
        if (bypassHosts == null) bypassHosts = List.of();
    }

    /** Defaults for disabled state (yml omits the block entirely). */
    public static ProxyProperties disabled() {
        return new ProxyProperties(false, "127.0.0.1", 0, List.of());
    }

    /** A proxy is usable only when enabled with a valid port. */
    public boolean usable() {
        return enabled && port > 0 && port <= 65535;
    }
}
