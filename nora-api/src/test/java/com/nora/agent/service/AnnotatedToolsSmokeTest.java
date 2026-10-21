package com.nora.agent.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.env.service.EnvironmentService;
import com.nora.env.service.ManagedSourceService;

/** 冒烟执行 SDK 契约、绑定和异常路径；业务结果由真实模型 E2E 验证。 */
class AnnotatedToolsSmokeTest {
    @Test
    void generatesContractsFromExecutionMethods() throws Exception {
        var mapper = new ObjectMapper();
        var tools = ToolSmokeFixtures.annotated(mapper);
        for (var specification : tools.specifications()) mapper.readTree(specification.toJson());
        tools.contains("execute_sql");
    }

    @Test
    void handlesMalformedAndMissingArguments() {
        var tools = ToolSmokeFixtures.annotated(new ObjectMapper());
        for (String args : List.of("{", "[]", "{}", "{\"sql\":\" \"}", "{\"sql\":\"select 1\",\"datasource\":\"unknown\"}")) {
            tools.execute("execute_sql", args, ToolSmokeFixtures.context());
        }
        for (String args : List.of("{}", "{\"target\":\"unknown\",\"limit\":\"invalid\"}",
                "{\"target\":\"unknown\",\"limit\":1.5}", "{\"target\":12}")) {
            tools.execute("manage_service", args, ToolSmokeFixtures.context());
        }
        for (String args : List.of("{}", "{\"target\":\"unknown\",\"action\":\"delete\"}",
                "{\"target\":\"unknown\",\"action\":\"restart\"}")) {
            tools.execute("manage_service", args, ToolSmokeFixtures.context());
        }
        tools.execute("manage_service", "{\"action\":\"status\"}", ToolSmokeFixtures.context());
    }

    @Test
    void rejectsWrongComplexTypesAndMixedFileIds() {
        var tools = ToolSmokeFixtures.annotated(new ObjectMapper());
        for (String args : List.of("{\"action\":\"delete\",\"id\":{}}", "{\"action\":\"delete\",\"id\":12}",
                "{\"action\":\"delete\",\"id\":\"12,invalid\"}", "{\"action\":\"delete\",\"id\":\"12,\"}",
                "{\"action\":\"delete\",\"id\":\"0\"}")) {
            tools.execute("manage_file", args, ToolSmokeFixtures.context());
        }
        tools.execute("manage_env_vars", "{\"action\":\"set\",\"key\":\"SMOKE\",\"value\":\"value\",\"secret\":\"private-value\"}", ToolSmokeFixtures.context());
        tools.execute("open_file", "{\"targets\":[false]}", ToolSmokeFixtures.context());
        tools.execute("manage_mcp", "{\"action\":\"call\",\"arguments\":\"{}\"}", ToolSmokeFixtures.context());
    }

    @Test
    void bindsDefaultsAndEnumWhileIgnoringDisplayTitle() {
        var environment = mock(EnvironmentService.class);
        var managed = mock(ManagedSourceService.class);
        when(managed.list()).thenReturn(List.of(new ManagedSourceService.SourceView(1, "DOCKER", "smoke",
                null, "smoke", null, null, true)));
        when(environment.logs(1, 50)).thenReturn(List.of("default-limit"));
        when(environment.control("smoke", "restart")).thenReturn(new EnvironmentService.StatusView("running", "ok"));
        var mapper = new ObjectMapper();
        var services = new ServiceTools(managed, new EnvironmentTools(environment),
                new ServiceLogTools(managed, environment), new ContainerTools(environment, managed));
        var tools = ToolSmokeFixtures.annotated(mapper, ToolSmokeFixtures.builtin(mapper, null, null, services, null, null, null, null),
                new FileWorkspaceTools(mock(FileTools.class), mock(AgentWorkspaceService.class), mock(RelayMediaRouter.class), mock(ViewerService.class)));
        tools.execute("manage_service", "{\"action\":\"logs\",\"target\":\"smoke\",\"description\":\"查看日志\"}", ToolSmokeFixtures.context());
        tools.execute("manage_service", "{\"target\":\"smoke\",\"action\":\"restart\"}", ToolSmokeFixtures.context());
    }

    @Test
    void retainsUnknownOutcomes() {
        var sql = mock(SqlTools.class);
        when(sql.executeSqlDetailed("write", "1")).thenReturn(new SqlTools.SqlOutcome("ERROR: unknown", null, false, true));
        var tools = ToolSmokeFixtures.annotated(new ObjectMapper(), sql, null, null);
        tools.execute("execute_sql", "{\"sql\":\"write\",\"datasource\":\"1\"}", ToolSmokeFixtures.context());
    }

    @Test
    void propagatesCancellationFromSdkExecution() {
        var sql = mock(SqlTools.class);
        when(sql.executeSqlDetailed("cancel", null)).thenThrow(new CancellationException("cancelled"));
        var tools = ToolSmokeFixtures.annotated(new ObjectMapper(), sql, null, null);
        try {
            tools.execute("execute_sql", "{\"sql\":\"cancel\"}", ToolSmokeFixtures.context());
        } catch (CancellationException expected) { }
    }
}
