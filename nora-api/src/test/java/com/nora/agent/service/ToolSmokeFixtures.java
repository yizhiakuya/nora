package com.nora.agent.service;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.datasource.service.DatasourceServiceImpl;
import com.nora.env.service.EnvironmentService;
import com.nora.env.service.ManagedSourceService;

final class ToolSmokeFixtures {
    private ToolSmokeFixtures() { }

    static AnnotatedTools annotated(ObjectMapper mapper) {
        return annotated(mapper, null, null, null);
    }

    static AnnotatedTools annotated(ObjectMapper mapper, SqlTools sql, ServiceLogTools logs, ContainerTools containers) {
        var environment = mock(EnvironmentService.class);
        var managed = mock(ManagedSourceService.class);
        var services = new ServiceTools(managed, new EnvironmentTools(environment),
                logs == null ? new ServiceLogTools(managed, environment) : logs,
                containers == null ? new ContainerTools(environment, managed) : containers);
        return new AnnotatedTools(mapper,
                sql == null ? new SqlTools(mock(DatasourceServiceImpl.class)) : sql,
                builtin(mapper, sql, null, services, null, null, null, null),
                new FileWorkspaceTools(mock(FileTools.class), mock(AgentWorkspaceService.class), mock(RelayMediaRouter.class), mock(ViewerService.class)),
                new McpManagementTools(mapper, mock(McpServerService.class), mock(GalleryPrefetcher.class)),
                new QuestionTools(new QuestionService(), new TurnStreamRegistry()));
    }

    static BuiltinTools builtin(ObjectMapper mapper, SqlTools sql, DataSourceTools datasource, ServiceTools service,
                                 TerminalService terminal, AgentSkillService skills, MediaFetchService media, AgentWorkspaceService workspace) {
        return new BuiltinTools(mapper, sql == null ? mock(SqlTools.class) : sql,
                datasource == null ? mock(DataSourceTools.class) : datasource, service == null ? mock(ServiceTools.class) : service,
                terminal == null ? mock(TerminalService.class) : terminal, skills == null ? mock(AgentSkillService.class) : skills,
                media == null ? mock(MediaFetchService.class) : media, mock(KnowledgeTools.class), mock(AutomationTools.class),
                mock(BrowserService.class), mock(PhotoAlbumService.class), mock(EnvVarsService.class), workspace);
    }

    static ToolCallContext context() { return new ToolCallContext("smoke", "step", null, null, false); }

    static AnnotatedTools annotated(ObjectMapper mapper, BuiltinTools builtin, FileWorkspaceTools files) {
        return new AnnotatedTools(mapper, mock(SqlTools.class), builtin, files,
                new McpManagementTools(mapper, mock(McpServerService.class), mock(GalleryPrefetcher.class)), new QuestionTools(new QuestionService(), new TurnStreamRegistry()));
    }
}
