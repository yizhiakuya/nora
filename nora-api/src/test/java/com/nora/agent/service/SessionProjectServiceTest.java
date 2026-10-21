package com.nora.agent.service;

import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionProjectServiceTest {
    @TempDir Path temporary;

    @Test
    void browsesDirectoriesAndRejectsInvalidPaths() throws Exception {
        var workspace = new AgentWorkspaceService(temporary.toString());
        var service = new SessionProjectService(mock(JdbcTemplate.class), workspace, new WorkspaceBootstrap(workspace));
        Path empty = Files.createDirectory(temporary.resolve("空目录 with spaces"));
        Path file = Files.writeString(temporary.resolve("file.txt"), "test");
        service.directories(null, null);
        service.directories(null, "");
        service.directories(null, empty.toString());
        for (String path : new String[] { "relative", file.toString(), temporary.resolve("missing").toString() }) {
            try { service.directories(null, path); }
            catch (IllegalArgumentException expected) { }
        }
        for (int index = 0; index < 502; index++) Files.createDirectory(temporary.resolve("dir-" + index));
        service.directories(null, temporary.toString());
    }
}
