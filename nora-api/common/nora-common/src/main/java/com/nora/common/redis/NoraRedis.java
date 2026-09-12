package com.nora.common.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Shared Redis facade for Nora services (platform component, architecture-v2.md).
 *
 * <p>Lifecycle: lazily connects on first use and reconnects transparently via
 * Lettuce's auto-reconnect. Every operation is best-effort — a missing or
 * unreachable Redis logs once and returns empty, so callers can fall back to
 * in-process behaviour instead of failing the request (Redis must never be a
 * hard dependency for boot or for serving traffic).
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

    /** Whether the feature flag is on (does not probe connectivity). */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * Runs a synchronous command against Redis, returning empty when disabled
     * or unreachable. Never throws for connectivity problems.
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
     * Opens (or reuses) the pub/sub connection for cross-instance notifications
     * (e.g. approval resolved in another instance). Returns empty when Redis is
     * disabled or unreachable.
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

    /** Publishes one message; silent no-op when Redis is unavailable. */
    public void publish(String channel, String message) {
        call(commands -> {
            commands.publish(channel, message);
            return null;
        });
    }

    /**
     * Fire-and-forget write on the async pipeline — never blocks the caller.
     * For hot paths (per-token SSE buffering) where a Redis stall must not
     * slow the stream; failures are logged once and the write is dropped.
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
                    // closing best-effort
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // closing best-effort
                }
            }
            if (client != null) {
                client.shutdown(Duration.ofMillis(100), Duration.ofMillis(300));
            }
        }
    }
}
