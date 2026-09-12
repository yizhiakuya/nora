package com.nora.datasource.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;

/**
 * Opens short-lived Lettuce connections from a stored {@code db_connection} row
 * with {@code engine = "redis"}.
 *
 * <p>Redis is not a JDBC database: the schema browse renders the key space and
 * the query console executes a guarded subset of read-only commands. Connections
 * are per-call (same interactive, not-hot-path reasoning as {@link JdbcConnections}).
 */
public final class RedisConnections {

    private RedisConnections() {
    }

    /** Redis connection parameters from a stored row. */
    public record Params(
            String host,
            Integer port,
            String username,
            String password,
            /** Redis logical database index (0-15); stored in the database column. */
            Integer databaseIndex) {

        /** Logical database index with a safe default. */
        public int db() {
            return databaseIndex == null ? 0 : Math.max(0, databaseIndex);
        }

        public RedisURI uri() {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(host == null || host.isBlank() ? "localhost" : host)
                    .withPort(port == null || port <= 0 ? 6379 : port)
                    .withDatabase(db())
                    // fail fast instead of hanging the request on a bad host
                    .withTimeout(Duration.ofSeconds(5));
            if (username != null && !username.isBlank()) {
                // Redis 6+ ACL username (password may be blank for user with nopass)
                builder.withAuthentication(username, password == null ? "" : password);
            } else if (password != null && !password.isBlank()) {
                builder.withPassword(password);
            }
            return builder.build();
        }
    }

    /**
     * Opens a Redis connection using the stored credentials. Callers must close
     * the returned client (which also closes its connection).
     */
    public static Open open(Params params) {
        RedisClient client = RedisClient.create(params.uri());
        StatefulRedisConnection<String, String> connection = client.connect();
        return new Open(client, connection);
    }

    /** Client + connection pair; closing the client releases both. */
    public record Open(RedisClient client, StatefulRedisConnection<String, String> connection)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                connection.close();
            } finally {
                client.shutdown(Duration.ofMillis(200), Duration.ofMillis(500));
            }
        }
    }
}
