package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;
import com.nora.datasource.api.Column;
import com.nora.datasource.api.ConnectionStatus;
import com.nora.datasource.api.DbTable;
import com.nora.datasource.api.QueryResult;
import com.nora.datasource.api.SchemaSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Core datasource operations: connection CRUD (with masked credentials),
 * connectivity test, schema introspection via {@link DatabaseMetaData}, and
 * guarded read-only query execution with persisted history.
 */
@Service
public class DatasourceServiceImpl {

    /** Max rows returned to the client (ACI principle: bounded, LLM-friendly payloads). */
    static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;

    public DatasourceServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Lists connections (passwords never returned; a masked hint is). */
    public List<ConnectionView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, engine, host, port, database, username, password, status FROM db_connection WHERE deleted_at IS NULL ORDER BY id",
                (rs, rowNum) -> new ConnectionView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("engine"),
                        rs.getString("host"),
                        rs.getObject("port") == null ? null : rs.getInt("port"),
                        rs.getString("database"),
                        rs.getString("username"),
                        mask(rs.getString("password")),
                        rs.getString("status")));
    }

    /** Creates a connection record. */
    public ConnectionView create(String name, String engine, String host, Integer port,
                                 String database, String username, String password) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        if (engine == null || !List.of("postgresql", "mysql").contains(engine)) {
            throw new BusinessException(400, "engine must be postgresql or mysql");
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO db_connection (name, engine, host, port, database, username, password, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'untested') RETURNING id",
                Long.class, name.trim(), engine, host, port, database, username, password);
        return get(id);
    }

    /** Soft-deletes a connection (row kept; history stays but becomes unreachable via filtered reads). */
    public boolean delete(long id) {
        return jdbcTemplate.update(
                "UPDATE db_connection SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
    }

    /** Loads one connection (masked). */
    public ConnectionView get(long id) {
        List<ConnectionView> rows = jdbcTemplate.query(
                "SELECT id, name, engine, host, port, database, username, password, status FROM db_connection WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new ConnectionView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("engine"),
                        rs.getString("host"),
                        rs.getObject("port") == null ? null : rs.getInt("port"),
                        rs.getString("database"),
                        rs.getString("username"),
                        mask(rs.getString("password")),
                        rs.getString("status")),
                id);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "connection not found: " + id);
        }
        return rows.get(0);
    }

    /** Tests connectivity: opens a real JDBC connection, measures latency. */
    public ConnectionStatus test(long id) {
        JdbcConnections.Params params = params(id);
        long start = System.currentTimeMillis();
        try (Connection ignored = JdbcConnections.open(params)) {
            long latency = System.currentTimeMillis() - start;
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            return new ConnectionStatus(true, "连接成功", latency);
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE db_connection SET status = 'error' WHERE id = ? AND deleted_at IS NULL", id);
            return new ConnectionStatus(false, shorten(e.getMessage()), null);
        }
    }

    /** Introspects the schema of the connected database. */
    public SchemaSnapshot schema(long id) {
        JdbcConnections.Params params = params(id);
        try (Connection conn = JdbcConnections.open(params)) {
            DatabaseMetaData meta = conn.getMetaData();
            List<DbTable> tables = new ArrayList<>();
            // schema 限定读取:不限 schema 时跨 schema 同名表(如各库的 flyway_schema_history)
            // 会在快照里重复且 getColumns(null, null, table) 会把不同 schema 的同名列合并
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String tableSchema = rs.getString("TABLE_SCHEM");
                    String table = rs.getString("TABLE_NAME");
                    List<Column> columns = new ArrayList<>();
                    try (ResultSet cols = meta.getColumns(null, tableSchema, table, "%")) {
                        while (cols.next()) {
                            columns.add(new Column(cols.getString("COLUMN_NAME"), cols.getString("TYPE_NAME")));
                        }
                    }
                    tables.add(new DbTable(tableSchema, table, columns));
                }
            }
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            return new SchemaSnapshot(tables);
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE db_connection SET status = 'error' WHERE id = ? AND deleted_at IS NULL", id);
            throw new BusinessException(502, "schema introspection failed: " + shorten(e.getMessage()));
        }
    }

    /**
     * Executes a guarded read-only statement: at most {@value #MAX_ROWS} rows,
     * cell values rendered as strings, execution persisted to query_history.
     */
    public QueryResult executeReadOnly(long id, String sql) {
        SqlGuard.requireReadOnly(sql);
        JdbcConnections.Params params = params(id);
        long start = System.currentTimeMillis();
        try (Connection conn = JdbcConnections.open(params);
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(MAX_ROWS);
            stmt.setQueryTimeout(30);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<String>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < MAX_ROWS) {
                    List<String> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(rs.getString(i));
                    }
                    rows.add(row);
                }
                long duration = System.currentTimeMillis() - start;
                saveHistory(id, sql, duration, rows.size(), "success");
                jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
                // 截断判定:maxRows 顶满即视为可能被截断(setMaxRows 让驱动在 200 行后停止拉取,
                // 无法区分"恰好 200 行"与"更多行被砍掉",保守标记,由展示层注明)
                boolean truncated = rows.size() >= MAX_ROWS;
                return new QueryResult(columns, rows, rows.size(), duration, truncated);
            }
        } catch (SQLException e) {
            saveHistory(id, sql, System.currentTimeMillis() - start, 0, "error");
            throw new BusinessException(502, "query failed: " + shorten(e.getMessage()));
        } catch (Exception e) {
            saveHistory(id, sql, System.currentTimeMillis() - start, 0, "error");
            throw new BusinessException(502, "query failed: " + shorten(e.getMessage()));
        }
    }

    /** Query history of a connection, newest first. */
    public List<HistoryView> history(long id, int limit) {
        return jdbcTemplate.query(
                "SELECT id, sql_text, duration_ms, rows_affected, status, executed_at FROM query_history "
                        + "WHERE connection_id = ? ORDER BY executed_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new HistoryView(
                        rs.getLong("id"),
                        rs.getString("sql_text"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getInt("rows_affected"),
                        rs.getString("status"),
                        rs.getTimestamp("executed_at")),
                id, limit);
    }

    /**
     * Executes a single write statement after the agent's approval flow.
     * Guarded: write verb only (agent-side RiskClassifier is advisory; this is
     * the enforcing layer), one statement, 30s timeout. Returns the affected
     * row count as a single-cell result. Persisted to query_history.
     */
    public QueryResult executeWrite(long id, String sql) {
        WriteGuard.requireWrite(sql);
        JdbcConnections.Params params = params(id);
        long start = System.currentTimeMillis();
        try (Connection conn = JdbcConnections.open(params);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(30);
            int affected = stmt.executeUpdate(sql);
            long duration = System.currentTimeMillis() - start;
            saveHistory(id, sql, duration, affected, "success");
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            return new QueryResult(
                    List.of("rows_affected"),
                    List.of(List.of(String.valueOf(affected))),
                    affected, duration, false);
        } catch (SQLException e) {
            saveHistory(id, sql, System.currentTimeMillis() - start, 0, "error");
            throw new BusinessException(502, "execute failed: " + shorten(e.getMessage()));
        } catch (Exception e) {
            saveHistory(id, sql, System.currentTimeMillis() - start, 0, "error");
            throw new BusinessException(502, "execute failed: " + shorten(e.getMessage()));
        }
    }

    private void saveHistory(long connectionId, String sql, long durationMs, int rows, String status) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO query_history (connection_id, sql_text, duration_ms, rows_affected, status) VALUES (?, ?, ?, ?, ?)",
                    connectionId, sql, durationMs, rows, status);
        } catch (Exception ignored) {
            // history is best-effort; never fail the query because of it
        }
    }

    private JdbcConnections.Params params(long id) {
        List<JdbcConnections.Params> rows = jdbcTemplate.query(
                "SELECT engine, host, port, database, username, password FROM db_connection WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new JdbcConnections.Params(
                        rs.getString("engine"),
                        rs.getString("host"),
                        rs.getObject("port") == null ? null : rs.getInt("port"),
                        rs.getString("database"),
                        rs.getString("username"),
                        rs.getString("password")),
                id);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "connection not found: " + id);
        }
        return rows.get(0);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "—";
        }
        return "••••••••" + secret.substring(Math.max(0, secret.length() - 2));
    }

    private static String shorten(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }

    /** Connection row as consumed by the frontend (password masked). */
    public record ConnectionView(
            long id,
            String name,
            String engine,
            String host,
            Integer port,
            String database,
            String username,
            String maskedPassword,
            String status) {
    }

    /** Query history row as consumed by the frontend. */
    public record HistoryView(
            long id,
            String sql,
            Long durationMs,
            int rowsAffected,
            String status,
            java.sql.Timestamp executedAt) {
    }
}
