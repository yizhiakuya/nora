package com.nora.datasource.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 从存储的 {@code db_connection} 行打开短命 JDBC 连接。
 *
 * <p>Phase 3 每次调用用普通 DriverManager 连接——schema 浏览与查询端点
 * 是交互式而非热路径,池化(每连接 HikariCP)推迟到 automation-service
 * 需要计划查询时。
 */
public final class JdbcConnections {

    private JdbcConnections() {
    }

    /** 从存储行来的连接参数。 */
    public record Params(
            String engine,
            String host,
            Integer port,
            String database,
            String username,
            String password) {

        /** 引擎的 JDBC URL。 */
        public String url() {
            return switch (engine) {
                case "postgresql" -> "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
                case "mysql" -> "jdbc:mysql://%s:%d/%s".formatted(host, port, database);
                default -> throw new IllegalArgumentException("unsupported engine: " + engine);
            };
        }
    }

    /**
     * 用已存凭证打开连接。
     *
     * @throws SQLException 驱动无法连接时
     */
    public static Connection open(Params params) throws SQLException {
        Properties props = new Properties();
        if (params.username() != null) {
            props.setProperty("user", params.username());
        }
        if (params.password() != null) {
            props.setProperty("password", params.password());
        }
        // 短连接超时:坏主机快速失败而不是挂起请求
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "30000");
        return DriverManager.getConnection(params.url(), props);
    }
}
