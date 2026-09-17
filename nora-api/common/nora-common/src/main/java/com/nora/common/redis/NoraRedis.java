package com.nora.common.redis;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Nora 服务的共享 Redis 门面(平台组件,architecture-v2.md)。
 *
 * <p>生命周期:首次使用时懒连接,经 Lettuce 自动重连透明恢复。每个操作都是
 * 尽力而为——Redis 缺失或不可达只记一次日志并返回空,调用方可落回进程内
 * 行为而不是让请求失败(Redis 绝不可成为启动或对外服务的硬依赖)。
 */
public final class NoraRedis implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NoraRedis.class);

    private final RedisProperties properties;
    private final Object lock = new Object();
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private volatile boolean loggedUnavailable = false;

    public NoraRedis(RedisProperties properties) {
        this.properties = properties;
    }

    /** 功能开关是否打开(不探测连通性)。 */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 对 Redis 执行同步命令,禁用或不可达时返回空。
     * 绝不因连通性问题抛异常。
     */
    public <T> Optional<T> call(java.util.function.Function<io.lettuce.core.api.sync.RedisCommands<String, String>, T> action) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            StatefulRedisConnection<String, String> conn = connection();
            if (conn == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(action.apply(conn.sync()));
        } catch (Exception e) {
            noteUnavailable(e);
            return Optional.empty();
        }
    }

    /**
     * 打开(或复用)跨实例通知的 pub/sub 连接
     * (如审批在另一实例被 resolve)。Redis 禁用或不可达时返回空。
     */
    public Optional<StatefulRedisPubSubConnection<String, String>> pubSub() {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        synchronized (lock) {
            try {
                if (pubSubConnection == null || !pubSubConnection.isOpen()) {
                    pubSubConnection = client().connectPubSub();
                }
                return Optional.of(pubSubConnection);
            } catch (Exception e) {
                noteUnavailable(e);
                return Optional.empty();
            }
        }
    }

    /** 发布一条消息;Redis 不可用时静默无操作。 */
    public void publish(String channel, String message) {
        call(commands -> {
            commands.publish(channel, message);
            return null;
        });
    }

    /**
     * 异步管线上的发后即忘写入——绝不阻塞调用方。用于热路径(逐 token SSE
     * 缓冲),Redis 卡顿不得拖慢流;失败记一次日志并丢弃写入。
     */
    public void callAsync(java.util.function.Function<RedisAsyncCommands<String, String>, io.lettuce.core.RedisFuture<?>> action) {
        if (!properties.enabled()) {
            return;
        }
        try {
            StatefulRedisConnection<String, String> conn = connection();
            if (conn == null) {
                return;
            }
            action.apply(conn.async());
        } catch (Exception e) {
            noteUnavailable(e);
        }
    }

    private StatefulRedisConnection<String, String> connection() {
        synchronized (lock) {
            if (connection != null && connection.isOpen()) {
                return connection;
            }
            try {
                connection = client().connect();
                loggedUnavailable = false;
                return connection;
            } catch (Exception e) {
                noteUnavailable(e);
                return null;
            }
        }
    }

    private RedisClient client() {
        synchronized (lock) {
            if (client == null) {
                RedisURI.Builder builder = RedisURI.builder()
                        .withHost(properties.host())
                        .withPort(properties.port())
                        .withDatabase(properties.database())
                        .withTimeout(Duration.ofMillis(properties.timeoutMs()));
                if (properties.password() != null && !properties.password().isBlank()) {
                    builder.withPassword(properties.password());
                }
                client = RedisClient.create(builder.build());
            }
            return client;
        }
    }

    private void noteUnavailable(Exception e) {
        if (!loggedUnavailable) {
            loggedUnavailable = true;
            log.warn("redis unavailable at {}:{} ({}), falling back to in-process behaviour",
                    properties.host(), properties.port(), e.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (pubSubConnection != null) {
                try {
                    pubSubConnection.close();
                } catch (Exception ignored) {
                    // 尽力关闭
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 尽力关闭
                }
            }
            if (client != null) {
                client.shutdown(Duration.ofMillis(100), Duration.ofMillis(300));
            }
        }
    }
}
