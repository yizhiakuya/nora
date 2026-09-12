package com.nora.env.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
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

        instance.id();
        instance.name();
        instance.image();
        instance.status();
        List.of("8080:8080");
        instance.ports();
        new ServiceInstance("abc123", "nora-agent",
                "nora/agent-service:latest", "running", List.of("8080:8080"));
    }

    @Test
    void logLineRecordRoundTrip() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 4, 12, 0, 0);
        LogLine line = new LogLine(now, "ERROR", "boom");

        line.time();
        line.level();
        line.message();
        new LogLine(null, null, null);
    }
}
