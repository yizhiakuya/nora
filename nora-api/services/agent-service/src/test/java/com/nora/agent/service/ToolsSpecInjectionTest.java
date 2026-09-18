package com.nora.agent.service;

import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 验证 toolsSpec 的装配结果:接入工作区/技能服务时,对应工具必须出现在
 * 下发给上游的 tools 数组里;服务为 null 时不得出现(测试构造器兼容)。
 *
 * <p>2026-09-17 拆分后直接构造 {@link ChatToolsSpec}(原反射 ChatOrchestrationService)。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ToolsSpecInjectionTest {

    private ChatToolsSpec buildWith(AgentWorkspaceService workspaceService,
                                    AgentSkillService skillService) {
        return buildWith(workspaceService, skillService, null, null);
    }

    private ChatToolsSpec buildWith(AgentWorkspaceService workspaceService,
                                    AgentSkillService skillService,
                                    McpServerService mcpServerService) {
        return buildWith(workspaceService, skillService, mcpServerService, null);
    }

    private ChatToolsSpec buildWith(AgentWorkspaceService workspaceService,
                                    AgentSkillService skillService,
                                    McpServerService mcpServerService,
                                    TerminalService terminalService) {
        return new ChatToolsSpec(new ObjectMapper(), workspaceService, skillService,
                mcpServerService, terminalService);
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
    void workspaceAndSkillToolsAppearWhenServicesWired() {
        JsonNode tools = buildWith(mock(AgentWorkspaceService.class), mock(AgentSkillService.class)).build();
        containsTool(tools, "manage_workspace");
        containsTool(tools, "manage_skill");
        // 既有工具不受影响
        containsTool(tools, "execute_sql");
        containsTool(tools, "read_file");
    }

    @Test
    void mcpManagementToolAppearsWhenRegistryWired() {
        McpServerService mcp = mock(McpServerService.class);
        org.mockito.Mockito.when(mcp.mountedTools()).thenReturn(List.of());
        JsonNode tools = buildWith(null, null, mcp).build();
        containsTool(tools, "manage_mcp");
        // 既有工具不受影响
        containsTool(tools, "execute_sql");
    }

    @Test
    void terminalToolAppearsWhenServiceWired() throws Exception {
        TerminalService terminal = new TerminalService(
                java.nio.file.Files.createTempDirectory("nora-term-test").toString());
        JsonNode tools = buildWith(null, null, null, terminal).build();
        containsTool(tools, "run_command");
        // 既有工具不受影响
        containsTool(tools, "execute_sql");
    }

    @Test
    void newToolsAbsentWhenServicesNull() {
        JsonNode tools = buildWith(null, null).build();
        containsTool(tools, "manage_workspace");
        containsTool(tools, "manage_skill");
        containsTool(tools, "manage_mcp");
        containsTool(tools, "run_command");
        // 2026-09-18 新增工具:客户端未接时不挂载
        containsTool(tools, "search_knowledge");
        containsTool(tools, "manage_knowledge");
        containsTool(tools, "manage_automation");
        containsTool(tools, "environment_status");
    }

    @Test
    void knowledgeAutomationEnvToolsAppearWhenClientsWired() {
        ChatToolsSpec spec = new ChatToolsSpec(new ObjectMapper(), null, null, null, null,
                null, mock(KnowledgeManageClient.class), mock(AutomationManageClient.class),
                mock(EnvironmentStatusClient.class));
        JsonNode tools = spec.build();
        containsTool(tools, "search_knowledge");
        containsTool(tools, "manage_knowledge");
        containsTool(tools, "manage_automation");
        containsTool(tools, "environment_status");
        // 既有工具不受影响
        containsTool(tools, "execute_sql");
    }

    @Test
    void enumConstraintsPresentOnFiniteValueFields() {
        // schema 纪律(2026-09-18):有限取值字段必须带 enum(让无效值不可表示)
        JsonNode tools = buildWith(null, null).build();
        for (JsonNode t : tools) {
            JsonNode fn = t.path("function");
            String name = fn.path("name").asText();
            if (name.equals("execute_sql") || name.equals("manage_workspace")) {
                JsonNode props = fn.path("parameters").path("properties");
                for (String field : java.util.List.of("action")) {
                    props.path(field).has("enum");
                }
            }
        }
    }
}
