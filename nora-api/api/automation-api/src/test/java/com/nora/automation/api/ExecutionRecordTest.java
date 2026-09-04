package com.nora.automation.api;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ExecutionRecordTest {

    @Test
    void newInstanceExposesAllFields() {
        Instant startedAt = Instant.parse("2026-09-04T12:00:00Z");
        ExecutionRecord record = new ExecutionRecord(
                42L, 7L, "success", 1500L, "restarted container web-1", startedAt);

        assertEquals(42L, record.id());
        assertEquals(7L, record.ruleId());
        assertEquals("success", record.status());
        assertEquals(1500L, record.durationMs());
        assertEquals("restarted container web-1", record.detail());
        assertEquals(startedAt, record.startedAt());
    }

    @Test
    void equalFieldsAreEqualAndDifferentFieldsAreNot() {
        Instant startedAt = Instant.parse("2026-09-04T12:00:00Z");
        ExecutionRecord first = new ExecutionRecord(1L, 7L, "success", 100L, "ok", startedAt);
        ExecutionRecord same = new ExecutionRecord(1L, 7L, "success", 100L, "ok", startedAt);
        ExecutionRecord other = new ExecutionRecord(2L, 7L, "failed", 100L, "boom", startedAt);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
    }
}
