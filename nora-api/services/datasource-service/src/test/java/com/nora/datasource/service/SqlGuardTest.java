package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlGuardTest {

    @Test
    void allowsSingleSelect() {
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("SELECT * FROM orders"));
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("select 1"));
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("SELECT 1;"));
    }

    @Test
    void allowsShowAndExplain() {
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("SHOW TABLES"));
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("EXPLAIN SELECT * FROM t"));
    }

    @Test
    void allowsLeadingComments() {
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("-- daily report\nSELECT 1"));
        assertDoesNotThrow(() -> SqlGuard.requireReadOnly("/* hint */ SELECT 1"));
    }

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
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> SqlGuard.requireReadOnly(sql), sql);
            assertEquals(400, ex.getCode());
        }
    }

    @Test
    void rejectsMultipleStatements() {
        assertThrows(BusinessException.class,
                () -> SqlGuard.requireReadOnly("SELECT 1; SELECT 2"));
        assertThrows(BusinessException.class,
                () -> SqlGuard.requireReadOnly("SELECT 1; DELETE FROM t"));
    }

    @Test
    void rejectsSelectInto() {
        assertThrows(BusinessException.class,
                () -> SqlGuard.requireReadOnly("SELECT * INTO new_table FROM orders"));
    }

    @Test
    void rejectsBlankAndUnknownVerbs() {
        assertThrows(BusinessException.class, () -> SqlGuard.requireReadOnly("   "));
        assertThrows(BusinessException.class, () -> SqlGuard.requireReadOnly(null));
        assertThrows(BusinessException.class, () -> SqlGuard.requireReadOnly("BEGIN"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireReadOnly("SET work_mem = '1GB'"));
    }
}
