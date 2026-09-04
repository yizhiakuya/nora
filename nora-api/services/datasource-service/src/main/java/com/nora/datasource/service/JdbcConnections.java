package com.nora.datasource.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens short-lived JDBC connections from a stored {@code db_connection} row.
 *
 * <p>Phase 3 uses plain DriverManager connections per call — the schema
 * browse and query endpoints are interactive, not hot paths, so a pool
 * (HikariCP per connection) is deferred until automation-service needs
 * scheduled queries.
 */
public final class JdbcConnections {

    private JdbcConnections() {
    }

    /** Connection parameters from a stored row. */
    public record Params(
            String engine,
            String host,
            Integer port,
            String database,
            String username,
            String password) {

        /** JDBC URL for the engine. */
        public String url() {
            return switch (engine) {
                case "postgresql" -> "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
                case "mysql" -> "jdbc:mysql://%s:%d/%s".formatted(host, port, database);
                default -> throw new IllegalArgumentException("unsupported engine: " + engine);
            };
        }
    }

    /**
     * Opens a connection using the stored credentials.
     *
     * @throws SQLException when the driver cannot connect
     */
    public static Connection open(Params params) throws SQLException {
        Properties props = new Properties();
        if (params.username() != null) {
            props.setProperty("user", params.username());
        }
        if (params.password() != null) {
            props.setProperty("password", params.password());
        }
        // short connect timeout so a bad host fails fast instead of hanging the request
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "30000");
        return DriverManager.getConnection(params.url(), props);
    }
}
