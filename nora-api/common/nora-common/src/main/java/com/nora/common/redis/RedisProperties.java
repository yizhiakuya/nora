package com.nora.common.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nora 服务的共享 Redis 设置({@code nora.redis.*})。
 *
 * <p>Redis 是平台组件(architecture-v2.md 第 1 节):嵌入缓存、审批票据等
 * 跨实例状态住在这里。禁用或不可达时服务落回进程内行为——
 * Redis 绝不可成为启动的硬依赖。
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

    /** 默认禁用(yml 可整块省略)。 */
    public static RedisProperties disabled() {
        return new RedisProperties(false, "localhost", 6379, null, 0, 2000);
    }
}
