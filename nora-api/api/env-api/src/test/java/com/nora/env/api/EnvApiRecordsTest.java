package com.nora.env.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnvApiRecordsTest {

    @Test
    void serviceInstanceRecordRoundTrip() {
        ServiceInstance instance = new ServiceInstance(
                "abc123",
                "nora-agent",
                "nora/agent-service:latest",
                "running",
                List.of("8080:8080")
        );

        assertEquals("abc123", instance.id());
        assertEquals("nora-agent", instance.name());
        assertEquals("nora/agent-service:latest", instance.image());
        assertEquals("running", instance.status());
        assertEquals(List.of("8080:8080"), instance.ports());
        assertEquals(instance, new ServiceInstance("abc123", "nora-agent",
                "nora/agent-service:latest", "running", List.of("8080:8080")));
    }

    @Test
    void logLineRecordRoundTrip() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 4, 12, 0, 0);
        LogLine line = new LogLine(now, "ERROR", "boom");

        assertEquals(now, line.time());
        assertEquals("ERROR", line.level());
        assertEquals("boom", line.message());
        assertNull(new LogLine(null, null, null).message());
    }
}
