package com.nora.agent.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 工具契约装配冒烟；工具行为由真实模型与 HTTP 验收。 */
class ToolsSpecInjectionTest {
    @TempDir Path workspace;

    @Test
    void buildsBuiltinToolContracts() {
        new ChatToolsSpec(new ObjectMapper(), ToolSmokeFixtures.annotated(new ObjectMapper()), null, null).build();
    }

    @Test
    void loadsOnlyRequestedToolsAndRestoresRetainedHistory() {
        var mapper = new ObjectMapper();
        var tools = ToolSmokeFixtures.annotated(mapper);
        var specs = new ChatToolsSpec(mapper, tools, null, null);
        specs.forMessages(List.of(WireMessage.user("普通任务")));
        var initial = specs.forMessages(List.of());
        System.out.println("Tool catalog: initial=" + initial.size() + ", all=" + specs.build().size()
                + ", initialChars=" + initial.stream().mapToInt(spec -> spec.toJson().length()).sum()
                + ", allChars=" + specs.build().stream().mapToInt(spec -> spec.toJson().length()).sum());
        var loaded = tools.execute("load_tools", "{\"names\":[\"manage_service\",\"manage_browser\"]}", ToolSmokeFixtures.context());
        specs.forMessages(List.of(WireMessage.tool("load", "load_tools", loaded.content())));
        specs.forMessages(List.of(WireMessage.user(loaded.content()), WireMessage.tool("external", "manage_mcp", loaded.content())));
        specs.forMessages(List.of(WireMessage.tool("invalid", "load_tools", "{已压缩")));
        for (String args : List.of("{}", "{\"names\":[]}", "{\"names\":[\"missing\"]}", "{\"names\":[12]}", "{\"names\":[null]}")) {
            tools.execute("load_tools", args, ToolSmokeFixtures.context());
        }
    }

    @Test
    void buildsContractsWithMcpRegistry() {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.mountedTools()).thenReturn(List.of());
        new ChatToolsSpec(new ObjectMapper(), ToolSmokeFixtures.annotated(new ObjectMapper()), mcp, null).build();
    }

    @Test
    void buildsPlatformTerminalContract() throws Exception {
        var terminal = TestServices.terminal(workspace);
        try {
            new ChatToolsSpec(new ObjectMapper(), ToolSmokeFixtures.annotated(new ObjectMapper()), null, terminal).build();
        } finally {
            terminal.shutdown();
        }
    }
}
