package com.nora.common.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared Redis settings for Nora services ({@code nora.redis.*}).
 *
 * <p>Redis is a platform component (architecture-v2.md section 1): embedding
 * caches, approval tickets and other cross-instance state live here. When
 * disabled or unreachable the services fall back to their in-process
 * behaviour — Redis must never be a hard dependency for boot.
 */
@ConfigurationProperties(prefix = "nora.redis")
public record RedisProperties(boolean enabled, String host, int port, String password, int database, long timeoutMs) {

    public RedisProperties {
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        if (port <= 0) {
            port = 6379;
        }
        if (database < 0) {
            database = 0;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 2000;
        }
    }

    /** Disabled default (yml omits the block entirely). */
    public static RedisProperties disabled() {
        return new RedisProperties(false, "localhost", 6379, null, 0, 2000);
    }
}
