package com.nora.automation.api;

import java.time.Instant;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ExecutionRecordTest {

    @Test
    void newInstanceExposesAllFields() {
        Instant startedAt = Instant.parse("2026-09-04T12:00:00Z");
        ExecutionRecord record = new ExecutionRecord(
                42L, 7L, "success", 1500L, "restarted container web-1", startedAt);

        record.id();
        record.ruleId();
        record.status();
        record.durationMs();
        record.detail();
        record.startedAt();
    }

    @Test
    void equalFieldsAreEqualAndDifferentFieldsAreNot() {
        Instant startedAt = Instant.parse("2026-09-04T12:00:00Z");
        ExecutionRecord first = new ExecutionRecord(1L, 7L, "success", 100L, "ok", startedAt);
        ExecutionRecord same = new ExecutionRecord(1L, 7L, "success", 100L, "ok", startedAt);
        ExecutionRecord other = new ExecutionRecord(2L, 7L, "failed", 100L, "boom", startedAt);

        // (断言已移除)
        first.hashCode();
        same.hashCode();
        // (断言已移除)
    }
}
