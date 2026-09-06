package com.nora.common.http;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

/**
 * Shared helpers to attach the configured outbound proxy to HTTP clients.
 *
 * <p>Policy: only destinations outside the LAN go through the proxy, so
 * service-to-service calls (localhost / 192.168.x / 172.16-31.x / 10.x)
 * always connect directly regardless of configuration.
 */
public final class ProxySupport {

    private ProxySupport() {
    }

    /**
     * Returns a {@link ProxySelector} for the target URL: the configured proxy
     * for external hosts, direct for LAN/loopback targets or when the proxy
     * is disabled. Never null — direct semantics when bypassing.
     */
    public static ProxySelector selectorFor(ProxyProperties props, String targetUrl) {
        if (props == null || !props.usable() || isLanTarget(targetUrl)) {
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
     * True when the proxy is usable and the target is external. Convenience
     * for clients that need the proxy address themselves (e.g. JDK HttpClient
     * builder takes the address directly).
     */
    public static InetSocketAddress addressFor(ProxyProperties props, String targetUrl) {
        if (props == null || !props.usable() || isLanTarget(targetUrl)) {
            return null;
        }
        return new InetSocketAddress(props.host(), props.port());
    }

    /**
     * LAN/loopback detection: localhost, 127.0.0.0/8, 10/8, 172.16/12,
     * 192.168/16, and *.local. Hostnames that don't parse as IPs are treated
     * as external (they need DNS + possibly the proxy anyway).
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
        // bare hostname (no dot) — service discovery name like "agent-service"
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

    /** Prevents unused warnings in tests that only exercise isLanTarget. */
    static List<String> supportedSchemes() {
        return List.of("http", "https");
    }
}
