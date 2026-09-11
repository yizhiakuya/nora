package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.config.LlmProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证 toolsSpec() 的装配结果:接入工作区/技能服务时,对应工具必须出现在
 * 下发给上游的 tools 数组里;服务为 null 时不得出现(测试构造器兼容)。
 */
class ToolsSpecInjectionTest {

    private ChatOrchestrationService buildWith(AgentWorkspaceService workspaceService,
                                               AgentSkillService skillService) throws Exception {
        return buildWith(workspaceService, skillService, null, null);
    }

    private ChatOrchestrationService buildWith(AgentWorkspaceService workspaceService,
                                               AgentSkillService skillService,
                                               McpServerService mcpServerService) throws Exception {
        return buildWith(workspaceService, skillService, mcpServerService, null);
    }

    private ChatOrchestrationService buildWith(AgentWorkspaceService workspaceService,
                                               AgentSkillService skillService,
                                               McpServerService mcpServerService,
                                               TerminalService terminalService) throws Exception {
        Constructor<ChatOrchestrationService> ctor = ChatOrchestrationService.class.getDeclaredConstructor(
                LlmProperties.class, RagRetrievalClient.class, SqlToolClient.class, ServiceLogClient.class,
                ObjectMapper.class, ModelProviderService.class, ApprovalService.class, WriteSqlClient.class,
                ContainerControlClient.class, DataSourceManageClient.class, ServiceManageClient.class,
                FileToolClient.class, McpServerService.class, AgentWorkspaceService.class, AgentSkillService.class,
                TerminalService.class, int.class, com.nora.common.http.ProxyProperties.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                new LlmProperties("key", "http://localhost:9/v1", "m"),
                mock(RagRetrievalClient.class), mock(SqlToolClient.class), mock(ServiceLogClient.class),
                new ObjectMapper(), null, null, null, null, null, null, null, mcpServerService,
                workspaceService, skillService, terminalService, 5, null);
    }

    private JsonNode toolsOf(ChatOrchestrationService svc) throws Exception {
        Method m = ChatOrchestrationService.class.getDeclaredMethod("toolsSpec");
        m.setAccessible(true);
        return (JsonNode) m.invoke(svc);
    }

    private boolean containsTool(JsonNode tools, String name) {
        for (JsonNode t : tools) {
            if (name.equals(t.path("function").path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void workspaceAndSkillToolsAppearWhenServicesWired() throws Exception {
        JsonNode tools = toolsOf(buildWith(mock(AgentWorkspaceService.class), mock(AgentSkillService.class)));
        assertTrue(containsTool(tools, "manage_workspace"), "manage_workspace 必须下发");
        assertTrue(containsTool(tools, "manage_skill"), "manage_skill 必须下发");
        // 既有工具不受影响
        assertTrue(containsTool(tools, "execute_sql"));
        assertTrue(containsTool(tools, "read_file"));
    }

    @Test
    void mcpManagementToolAppearsWhenRegistryWired() throws Exception {
        McpServerService mcp = mock(McpServerService.class);
        org.mockito.Mockito.when(mcp.mountedTools()).thenReturn(List.of());
        JsonNode tools = toolsOf(buildWith(null, null, mcp));
        assertTrue(containsTool(tools, "manage_mcp"), "manage_mcp 必须下发");
        // 既有工具不受影响
        assertTrue(containsTool(tools, "execute_sql"));
    }

    @Test
    void terminalToolAppearsWhenServiceWired() throws Exception {
        TerminalService terminal = new TerminalService(
                java.nio.file.Files.createTempDirectory("nora-term-test").toString());
        JsonNode tools = toolsOf(buildWith(null, null, null, terminal));
        assertTrue(containsTool(tools, "run_command"), "run_command 必须下发");
        // 既有工具不受影响
        assertTrue(containsTool(tools, "execute_sql"));
    }

    @Test
    void newToolsAbsentWhenServicesNull() throws Exception {
        JsonNode tools = toolsOf(buildWith(null, null));
        assertTrue(!containsTool(tools, "manage_workspace"), "无服务时不应下发");
        assertTrue(!containsTool(tools, "manage_skill"), "无服务时不应下发");
        assertTrue(!containsTool(tools, "manage_mcp"), "无 MCP 服务时不应下发");
        assertTrue(!containsTool(tools, "run_command"), "无终端服务时不应下发");
    }
}
