package com.nora.agent.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;

class McpResultTest {
    @Test
    void rendersStructuredDataAndResourceContent() {
        var service = new McpServerService(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class), new ObjectMapper(),
                new RelayMediaRouter(new ObjectMapper(), "", 120), false, org.mockito.Mockito.mock(EnvVarsService.class));
        service.renderResultRich(new McpSchema.CallToolResult(List.of(), false, Map.of("count", 3), null));
        var resource = new McpSchema.EmbeddedResource(null,
                new McpSchema.TextResourceContents("audit://text", "text/plain", "embedded text", null), null);
        var link = McpSchema.ResourceLink.builder().name("receipt").uri("audit://receipt").mimeType("text/plain").build();
        service.renderResultRich(new McpSchema.CallToolResult(List.of(resource, link), false, Map.of("count", 3), null));
        service.shutdown();
    }
}
