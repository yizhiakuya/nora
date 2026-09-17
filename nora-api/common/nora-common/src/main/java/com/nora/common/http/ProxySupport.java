package com.nora.common.http;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

/**
 * 把配置的出站代理挂到 HTTP 客户端上的共享辅助。
 *
 * <p>策略:只有 LAN 之外的目标走代理,服务间调用(localhost / 192.168.x /
 * 172.16-31.x / 10.x)无论配置如何一律直连。
 */
public final class ProxySupport {

    private ProxySupport() {
    }

    /**
     * 返回目标 URL 的 {@link ProxySelector}:外网主机用配置的代理,
     * LAN/回环目标或代理禁用时直连。绝不返回 null——绕过时即直连语义。
     */
    public static ProxySelector selectorFor(ProxyProperties props, String targetUrl) {
        if (props == null || !props.usable() || isLanTarget(targetUrl) || isBypassHost(props, targetUrl)) {
            return ProxySelector.getDefault() != null
                    ? ProxySelector.getDefault()
                    : new ProxySelector() {
                        @Override
                        public java.util.List<java.net.Proxy> select(URI uri) {
                            return java.util.List.of(java.net.Proxy.NO_PROXY);
                        }

                        @Override
                        public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                        }
                    };
        }
        return ProxySelector.of(new InetSocketAddress(props.host(), props.port()));
    }

    /**
     * 代理可用且目标为外网时返回 true。供需要自己拿代理地址的客户端使用
     * (如 JDK HttpClient builder 直接收地址)。
     */
    public static InetSocketAddress addressFor(ProxyProperties props, String targetUrl) {
        if (props == null || !props.usable() || isLanTarget(targetUrl) || isBypassHost(props, targetUrl)) {
            return null;
        }
        return new InetSocketAddress(props.host(), props.port());
    }

    /**
     * bypass-hosts 名单匹配：域名等于/后缀匹配任一条目即直连。
     *
     * <p>用途：某些「公网域名」实际指向自家内网（如媒体中继 home.rainaki.top），
     * 经代理远程节点绕行反而慢（实测 +2.3s）。配到 nora.proxy.bypass-hosts 后
     * 这些域名一律直连。匹配大小写不敏感；条目可带前导点（.rainaki.top）。
     */
    public static boolean isBypassHost(ProxyProperties props, String url) {
        if (props == null || props.bypassHosts() == null || props.bypassHosts().isEmpty()) {
            return false;
        }
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        for (String raw : props.bypassHosts()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim().toLowerCase();
            if (entry.startsWith(".")) {
                // 后缀式:".rainaki.top" 匹配 a.rainaki.top 与 rainaki.top
                if (h.equals(entry.substring(1)) || h.endsWith(entry)) {
                    return true;
                }
            } else if (h.equals(entry) || h.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    /** 从 URL 提取小写主机名;解析失败返回 null。 */
    private static String hostOf(String url) {
        try {
            String work = url == null ? "" : url.trim();
            if (!work.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                work = "http://" + work;
            }
            return URI.create(work).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * LAN/回环检测:localhost、127.0.0.0/8、10/8、172.16/12、192.168/16
     * 与 *.local。解析不成 IP 的主机名按外网处理(反正要 DNS,可能也要代理)。
     */
    public static boolean isLanTarget(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String host;
        try {
            String work = url.trim();
            if (!work.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                work = "http://" + work;
            }
            host = URI.create(work).getHost();
        } catch (Exception e) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase();
        if ("localhost".equals(h) || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        // 裸主机名(无点)——如 "agent-service" 这类服务发现名
        if (!h.matches(".*\\d+.*") && !h.contains(".")) {
            return true;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matcher(h);
        if (!m.matches()) {
            return false; // DNS name with dots → external
        }
        try {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a == 127 || a == 10) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            if (a == 169 && b == 254) {
                return true;
            }
        } catch (NumberFormatException ignored) {
            return true;
        }
        return false;
    }

    /** 防止只测 isLanTarget 的测试出现未用警告。 */
    static List<String> supportedSchemes() {
        return List.of("http", "https");
    }
}
