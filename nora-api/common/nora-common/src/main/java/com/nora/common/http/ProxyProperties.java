package com.nora.common.http;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外网(非 LAN)HTTP 调用的出站代理设置。
 *
 * <p>范围:仅后端出站(LLM 上游、嵌入 provider 等)。LAN 内的服务间调用
 * 绝不走代理——{@link ProxySupport#isLanTarget(String)} 会绕过它。
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

    /** 禁用状态的默认值(yml 可整块省略)。 */
    public static ProxyProperties disabled() {
        return new ProxyProperties(false, "127.0.0.1", 0, List.of());
    }

    /** 代理仅在启用且端口有效时可用。 */
    public boolean usable() {
        return enabled && port > 0 && port <= 65535;
    }
}
