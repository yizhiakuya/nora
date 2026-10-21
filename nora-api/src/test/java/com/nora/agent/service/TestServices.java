package com.nora.agent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import com.nora.agent.dto.CommandExecutionDto;

/** 命令冒烟仍启动真实进程，持久化依赖由测试提供。 */
final class TestServices {
    private TestServices() { }

    static TerminalService terminal(Path workspace) {
        var settings = mock(AppSettingStore.class);
        return terminal(workspace, settings, new EnvVarsService(settings));
    }

    static TerminalService terminal(Path workspace, AppSettingStore settings, EnvVarsService variables) {
        var snapshots = new ConcurrentHashMap<String, CommandExecutionDto>();
        var store = mock(CommandExecutionStore.class);
        doAnswer(call -> {
            CommandExecutionDto snapshot = call.getArgument(0);
            snapshots.put(snapshot.executionId(), snapshot);
            return null;
        }).when(store).save(any());
        when(store.find(any(), any())).thenAnswer(call -> {
            CommandExecutionDto snapshot = snapshots.get(call.getArgument(1));
            if (snapshot == null || !snapshot.sessionId().equals(call.getArgument(0)))
                throw new IllegalArgumentException("命令任务不存在");
            return snapshot;
        });
        var projects = mock(SessionProjectService.class);
        when(projects.root(any())).thenReturn(workspace.toAbsolutePath().normalize());
        return new TerminalService(workspace.toString(), "", TerminalService.DEFAULT_PWSH_BUNDLE,
                settings, variables, store, new TurnStreamRegistry(), projects);
    }
}
