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
        return open(params, false);
    }

    /**
     * 用已存凭证打开连接。
     *
     * @param readOnly true 时把连接置为**数据库级只读**——这是独立于 SQL 语句
     *                 检查（SqlGuard）的硬边界：PG/MySQL 会直接拒绝写操作，包括
     *                 「EXPLAIN ANALYZE 包裹写 CTE」这类语句解析难以穷尽的绕过
     *                 （2026-09-19 审查项）。实测（PG 16 + JDBC 42.7.11）：
     *                 <ul>
     *                   <li>{@code readOnly=true} 单独只是客户端提示，服务端不拦
     *                       （写 CTE 的 EXPLAIN ANALYZE 被放行）；</li>
     *                   <li>{@code readOnly=true + readOnlyMode=always} 才下发
     *                       {@code SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY}，
     *                       写操作报 "cannot execute ... in a read-only transaction"。</li>
     *                 </ul>
     * @throws SQLException 驱动无法连接、或驱动不支持只读时（fail-closed：宁拒绝不裸跑）
     */
    public static Connection open(Params params, boolean readOnly) throws SQLException {
        Properties props = new Properties();
        if (params.username() != null) {
            props.setProperty("user", params.username());
        }
        if (params.password() != null) {
            props.setProperty("password", params.password());
        }
        // 短连接超时:坏主机快速失败而不是挂起请求
        // PostgreSQL 驱动用秒,MySQL Connector/J 用毫秒。
        boolean postgres = "postgresql".equals(params.engine());
        props.setProperty("connectTimeout", postgres ? "5" : "5000");
        props.setProperty("socketTimeout", postgres ? "30" : "30000");
        if (readOnly) {
            switch (params.engine()) {
                case "postgresql" -> {
                    // 注意:readOnly 单独不生效(实测),readOnlyMode=always 才真正下发
                    props.setProperty("readOnly", "true");
                    props.setProperty("readOnlyMode", "always");
                }
                case "mysql" -> // Connector/J 8 默认即传播,显式声明防版本漂移
                        props.setProperty("readOnlyPropagatesToServer", "true");
                default -> {
                    // 未知引擎不该走到这(上层已限定),防御性拒绝
                }
            }
        }
        Connection conn = DriverManager.getConnection(params.url(), props);
        if (readOnly) {
            try {
                // MySQL 经这一步下发 SET SESSION TRANSACTION READ ONLY;
                // PG 在 readOnlyMode=always 下已于连接时生效,重复调用无害
                conn.setReadOnly(true);
            } catch (SQLException e) {
                // 驱动不支持只读 → fail-closed:宁可拒绝本次查询,不裸跑一个
                // 无保护的"只读"连接(语句检查仍是第一道防线,但不够)
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                throw new SQLException("数据库驱动不支持只读连接,已拒绝执行以保证查询不会写库: " + e.getMessage(), e);
            }
        }
        return conn;
    }
}
