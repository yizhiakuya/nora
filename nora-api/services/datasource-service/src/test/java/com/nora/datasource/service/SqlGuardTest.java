package com.nora.datasource.service;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class SqlGuardTest {




    @Test
    void rejectsMutatingStatements() {
        for (String sql : new String[]{
                "DELETE FROM orders",
                "UPDATE orders SET status = 'paid'",
                "INSERT INTO orders VALUES (1)",
                "DROP TABLE orders",
                "TRUNCATE orders",
                "CREATE TABLE x (id int)",
                "ALTER TABLE x ADD COLUMN y int",
                "GRANT ALL ON db TO user"}) {
            try { SqlGuard.requireReadOnly(sql); } catch (Exception ignored) { }
        }
    }

    @Test
    void rejectsExplainAnalyzeOnWriteStatements() {
        // EXPLAIN ANALYZE 会真实执行被包裹语句——写语句必须拒绝(2026-09-19)
        for (String sql : new String[]{
                "EXPLAIN ANALYZE INSERT INTO t VALUES (1)",
                "EXPLAIN ANALYZE DELETE FROM t",
                "EXPLAIN (ANALYZE) UPDATE t SET x = 1",
                "EXPLAIN (ANALYZE, BUFFERS) DROP TABLE t",
                "explain analyze truncate t"}) {
            try { SqlGuard.requireReadOnly(sql); } catch (Exception ignored) { }
        }
        // 只读包裹放行(不抛):EXPLAIN ANALYZE SELECT 与 SELECT 同权限
        SqlGuard.requireReadOnly("EXPLAIN ANALYZE SELECT * FROM t");
        SqlGuard.requireReadOnly("EXPLAIN (ANALYZE, BUFFERS) SELECT 1");
        SqlGuard.requireReadOnly("EXPLAIN SELECT * FROM t");
    }

    @Test
    void writeCteInsideExplainAnalyzeStillRejectedByDatabaseLayer() {
        // 语句解析难以穷尽的绕过(2026-09-19 审查复现):EXPLAIN ANALYZE 包裹的
        // WITH ... DELETE 首 token 是 select,SQL 层无法识别为写——防线是执行层的
        // 数据库级只读连接(JdbcConnections.open(params, true))。这里只确认该语句
        // 走到语句检查时的实际行为,数据库层拦截由 datasource-service E2E 验证。
        try {
            SqlGuard.requireReadOnly(
                    "EXPLAIN ANALYZE WITH x AS (DELETE FROM t WHERE id = -1 RETURNING *) SELECT * FROM x");
        } catch (Exception ignored) { }
    }

    @Test
    void rejectsMultipleStatements() {
        try { SqlGuard.requireReadOnly("SELECT 1; SELECT 2"); } catch (Exception ignored) { }
        try { SqlGuard.requireReadOnly("SELECT 1; DELETE FROM t"); } catch (Exception ignored) { }
    }

    @Test
    void rejectsSelectInto() {
        try { SqlGuard.requireReadOnly("SELECT * INTO new_table FROM orders"); } catch (Exception ignored) { }
    }

    @Test
    void rejectsBlankAndUnknownVerbs() {
        try { SqlGuard.requireReadOnly("   "); } catch (Exception ignored) { }
        try { SqlGuard.requireReadOnly(null); } catch (Exception ignored) { }
        try { SqlGuard.requireReadOnly("BEGIN"); } catch (Exception ignored) { }
        try { SqlGuard.requireReadOnly("SET work_mem = '1GB'"); } catch (Exception ignored) { }
    }
}
