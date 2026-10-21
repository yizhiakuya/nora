package com.nora.agent.service;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nora.env.service.EnvironmentService;

class EnvironmentToolsTest {
    @Test
    void rendersObserverAndUnprobedLogSources() {
        var environment = mock(EnvironmentService.class);
        when(environment.services()).thenReturn(List.of(
                Map.of("name", "log", "kind", "FILE", "health", "unknown", "status", "unknown",
                        "observer", Map.of("host", "Windows-host", "platform", "Windows 11", "scope", "host")),
                Map.of("name", "container", "kind", "DOCKER", "health", "healthy", "status", "running"),
                Map.of("name", "stopped", "kind", "PROC", "health", "down", "status", "stopped")));
        new EnvironmentTools(environment).statusSummary();
    }
}
