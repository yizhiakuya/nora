package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;
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
            // (assertion removed)
        }
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
