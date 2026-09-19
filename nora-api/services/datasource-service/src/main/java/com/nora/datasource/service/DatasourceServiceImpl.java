package com.nora.datasource.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.nora.common.exception.BusinessException;
import com.nora.datasource.api.Column;
import com.nora.datasource.api.ConnectionStatus;
import com.nora.datasource.api.DbTable;
import com.nora.datasource.api.QueryResult;
import com.nora.datasource.api.SchemaSnapshot;

/**
 * 数据源核心操作:连接 CRUD(凭证脱敏)、连通测试、经 {@link DatabaseMetaData}
 * 的 schema 内省,以及带历史落库的受控只读查询执行。
 */
@Service
public class DatasourceServiceImpl {

    /** 返回给客户端的最大行数(ACI 原则:有界、LLM 友好载荷)。 */
    static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;

    public DatasourceServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 列出连接(绝不返回密码;返回脱敏提示)。 */
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

    /** 创建连接记录。 */
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

    /** 软删连接(行保留;历史仍在但经过滤读不可达)。 */
    public boolean delete(long id) {
        return jdbcTemplate.update(
                "UPDATE db_connection SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
    }

    /** 加载单个连接(脱敏)。 */
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

    /** 测试连通:打开真实 JDBC 连接并测延迟。 */
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

    /** 测试 Redis 连接:PING 往返并测延迟。 */
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

    /** 内省所连数据库的 schema。 */
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

    /** 把 Redis 键空间渲染为通用 schema 形态。 */
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
     * 执行受控只读语句:最多 {@value #MAX_ROWS} 行,单元格值渲染为字符串,
     * 执行落 query_history。
     *
     * <p>安全(2026-09-19 加固):连接以**数据库级只读**打开(见
     * {@link JdbcConnections#open(JdbcConnections.Params, boolean)})——
     * 写操作在服务端被直接拒绝,包括「EXPLAIN ANALYZE 包裹写 CTE」这类
     * {@link SqlGuard} 语句解析难以穷尽的绕过(实测 PG:写 CTE 的 EXPLAIN
     * ANALYZE 在只读事务下报 cannot execute ... in a read-only transaction)。
     * 语句检查仍保留作第一道防线与更友好的错误提示。
     */
    public QueryResult executeReadOnly(long id, String sql) {
        if (isRedis(id)) {
            return executeRedis(id, sql);
        }
        SqlGuard.requireReadOnly(sql);
        JdbcConnections.Params params = params(id);
        long start = System.currentTimeMillis();
        try (Connection conn = JdbcConnections.open(params, true);
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
            // 25006 = read_only_sql_transaction:语句被数据库只读边界拦下
            // (如 EXPLAIN ANALYZE 包裹写 CTE——语句解析识别不出,数据库能识别)。
            // 给可操作提示而不是笼统 502。
            if ("25006".equals(e.getSQLState())) {
                throw new BusinessException(400, "该语句试图执行写操作,已被数据库只读约束拒绝"
                        + "(只读通道仅执行 SELECT/SHOW/EXPLAIN;写操作请用受控写通道)");
            }
            throw new BusinessException(502, "query failed: " + shorten(e.getMessage()));
        } catch (Exception e) {
            saveHistory(id, sql, System.currentTimeMillis() - start, 0, "error");
            throw new BusinessException(502, "query failed: " + shorten(e.getMessage()));
        }
    }

    /** 连接的查询历史,最新在前。 */
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

    /** 执行受控只读 Redis 命令(控制台路径)。 */
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

    /** 连接引擎为 redis 时 true。 */
    private boolean isRedis(long id) {
        String engine = jdbcTemplate.query(
                "SELECT engine FROM db_connection WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getString("engine"), id).stream().findFirst().orElse(null);
        if (engine == null) {
            throw new BusinessException(404, "connection not found: " + id);
        }
        return "redis".equals(engine);
    }

    /** Redis 连接参数(database 列存逻辑 db 序号)。 */
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

    /** 从 database 列取 Redis 逻辑 db 序号(默认 0)。 */
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
     * 在 agent 审批流之后执行单条写语句。受控:仅写动词(agent 侧 RiskClassifier
     * 是建议性的;这里是强制层)、单条语句、30s 超时。返回受影响行数作为单格结果。
     * 落 query_history。
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
            // 历史是尽力而为;绝不因它让查询失败
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

    /** 前端消费的连接行(密码脱敏)。 */
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

    /** 前端消费的查询历史行。 */
    public record HistoryView(
            long id,
            String sql,
            Long durationMs,
            int rowsAffected,
            String status,
            java.sql.Timestamp executedAt) {
    }
}
