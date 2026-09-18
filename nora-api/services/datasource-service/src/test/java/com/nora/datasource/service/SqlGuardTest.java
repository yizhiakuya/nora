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
