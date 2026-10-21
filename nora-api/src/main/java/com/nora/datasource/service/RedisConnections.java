package com.nora.datasource.service;

import java.time.Duration;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * 从 {@code engine = "redis"} 的存储 {@code db_connection} 行打开短命
 * Lettuce 连接。
 *
 * <p>Redis 不是 JDBC 数据库:schema 浏览渲染键空间,查询控制台执行受控的
 * 只读命令子集。连接按调用创建(与 {@link JdbcConnections} 相同的交互式
 * 而非热路径理由)。
 */
public final class RedisConnections {

    private RedisConnections() {
    }

    /** 从存储行来的 Redis 连接参数。 */
    public record Params(
            String host,
            Integer port,
            String username,
            String password,
            /** Redis 逻辑数据库序号(0-15);存于 database 列。 */
            Integer databaseIndex) {

        /** 带安全默认值的逻辑数据库序号。 */
        public int db() {
            return databaseIndex == null ? 0 : Math.max(0, databaseIndex);
        }

        public RedisURI uri() {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(host == null || host.isBlank() ? "localhost" : host)
                    .withPort(port == null || port <= 0 ? 6379 : port)
                    .withDatabase(db())
                    // 坏主机快速失败而不是挂起请求
                    .withTimeout(Duration.ofSeconds(5));
            if (username != null && !username.isBlank()) {
                // Redis 6+ ACL 用户名(启用 nopass 的用户密码可为空)
                builder.withAuthentication(username, password == null ? "" : password);
            } else if (password != null && !password.isBlank()) {
                builder.withPassword(password);
            }
            return builder.build();
        }
    }

    /**
     * 用已存凭证打开 Redis 连接。调用方必须关闭返回的 client(同时关闭其连接)。
     */
    public static Open open(Params params) {
        RedisClient client = RedisClient.create(params.uri());
        StatefulRedisConnection<String, String> connection = client.connect();
        return new Open(client, connection);
    }

    /** client + 连接对;关闭 client 即释放两者。 */
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
