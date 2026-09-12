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
        if (engine == null || !List.of("postgresql", "mysql", "redis").contains(engine)) {
            throw new BusinessException(400, "engine must be postgresql, mysql or redis");
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
        if (isRedis(id)) {
            return testRedis(id);
        }
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

    /** Tests a Redis connection: PING round-trip, measures latency. */
    private ConnectionStatus testRedis(long id) {
        long start = System.currentTimeMillis();
        try (RedisConnections.Open open = RedisConnections.open(redisParams(id))) {
            String pong = open.connection().sync().ping();
            long latency = System.currentTimeMillis() - start;
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            return new ConnectionStatus(true, "连接成功 (" + pong + ")", latency);
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE db_connection SET status = 'error' WHERE id = ? AND deleted_at IS NULL", id);
            return new ConnectionStatus(false, shorten(e.getMessage()), null);
        }
    }

    /** Introspects the schema of the connected database. */
    public SchemaSnapshot schema(long id) {
        if (isRedis(id)) {
            return schemaRedis(id);
        }
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
                    String tableComment = rs.getString("REMARKS");
                    // 主键列集合:DatabaseMetaData.getColumns 不返回 PK 标记,需单独取
                    java.util.Set<String> pkColumns = new java.util.HashSet<>();
                    try (ResultSet pk = meta.getPrimaryKeys(null, tableSchema, table)) {
                        while (pk.next()) {
                            pkColumns.add(pk.getString("COLUMN_NAME"));
                        }
                    }
                    List<Column> columns = new ArrayList<>();
                    try (ResultSet cols = meta.getColumns(null, tableSchema, table, "%")) {
                        while (cols.next()) {
                            String colName = cols.getString("COLUMN_NAME");
                            String remarks = cols.getString("REMARKS");
                            columns.add(new Column(
                                    colName,
                                    cols.getString("TYPE_NAME"),
                                    remarks == null ? "" : remarks,
                                    cols.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                    pkColumns.contains(colName),
                                    cols.getString("COLUMN_DEF")));
                        }
                    }
                    tables.add(new DbTable(tableSchema, table, tableComment == null ? "" : tableComment, columns));
                }
            }
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            return new SchemaSnapshot(tables);
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE db_connection SET status = 'error' WHERE id = ? AND deleted_at IS NULL", id);
            throw new BusinessException(502, "schema introspection failed: " + shorten(e.getMessage()));
        }
    }

    /** Renders the Redis key space into the generic schema shape. */
    private SchemaSnapshot schemaRedis(long id) {
        try (RedisConnections.Open open = RedisConnections.open(redisParams(id))) {
            SchemaSnapshot snapshot = RedisSchema.snapshot(open.connection().sync(), redisParams(id).db());
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            return snapshot;
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE db_connection SET status = 'error' WHERE id = ? AND deleted_at IS NULL", id);
            throw new BusinessException(502, "Redis key space scan failed: " + shorten(e.getMessage()));
        }
    }

    /**
     * Executes a guarded read-only statement: at most {@value #MAX_ROWS} rows,
     * cell values rendered as strings, execution persisted to query_history.
     */
    public QueryResult executeReadOnly(long id, String sql) {
        if (isRedis(id)) {
            return executeRedis(id, sql);
        }
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

    /** Executes a guarded read-only Redis command (console path). */
    private QueryResult executeRedis(long id, String command) {
        List<String> argv = RedisGuard.requireReadOnly(command);
        long start = System.currentTimeMillis();
        try (RedisConnections.Open open = RedisConnections.open(redisParams(id))) {
            QueryResult result = RedisExecutor.execute(open.connection().sync(), argv);
            long duration = System.currentTimeMillis() - start;
            saveHistory(id, command, duration, result.rowCount(), "success");
            jdbcTemplate.update("UPDATE db_connection SET status = 'connected' WHERE id = ? AND deleted_at IS NULL", id);
            // 统一补耗时(执行器内部不知道连接耗时)
            return new QueryResult(result.columns(), result.rows(), result.rowCount(), duration, result.truncated());
        } catch (BusinessException e) {
            saveHistory(id, command, System.currentTimeMillis() - start, 0, "error");
            throw e;
        } catch (Exception e) {
            saveHistory(id, command, System.currentTimeMillis() - start, 0, "error");
            throw new BusinessException(502, "Redis 命令执行失败: " + shorten(e.getMessage()));
        }
    }

    /** True when the connection's engine is redis. */
    private boolean isRedis(long id) {
        String engine = jdbcTemplate.query(
                "SELECT engine FROM db_connection WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getString("engine"), id).stream().findFirst().orElse(null);
        if (engine == null) {
            throw new BusinessException(404, "connection not found: " + id);
        }
        return "redis".equals(engine);
    }

    /** Redis connection parameters (database column holds the logical db index). */
    private RedisConnections.Params redisParams(long id) {
        List<RedisConnections.Params> rows = jdbcTemplate.query(
                "SELECT host, port, username, password, database FROM db_connection WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new RedisConnections.Params(
                        rs.getString("host"),
                        rs.getObject("port") == null ? null : rs.getInt("port"),
                        rs.getString("username"),
                        rs.getString("password"),
                        parseDbIndex(rs.getString("database"))),
                id);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "connection not found: " + id);
        }
        return rows.get(0);
    }

    /** Redis logical db index from the database column (defaults to 0). */
    private static Integer parseDbIndex(String database) {
        if (database == null || database.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(database.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
