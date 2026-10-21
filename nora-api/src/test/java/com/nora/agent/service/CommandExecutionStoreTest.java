package com.nora.agent.service;

import static org.mockito.Mockito.mock;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.CommandExecutionDto;

class CommandExecutionStoreTest {
    @TempDir Path directory;

    @Test
    void savesInterruptedTerminalAndRecoversJournal() throws Exception {
        var mapper = new ObjectMapper();
        var workspace = new AgentWorkspaceService(directory.toString());
        var store = new CommandExecutionStore(mock(JdbcTemplate.class), mapper, workspace);
        var result = new CommandExecutionDto("smoke", "session", null, null, "smoke", "echo ok", directory.toString(),
                "sh", "completed", 1, 2L, 1, 30, 1, 2, "ok", "workspace:command-logs/smoke.log", 0, null);
        Thread.currentThread().interrupt();
        try { store.save(result); }
        finally { Thread.interrupted(); }
        mapper.writeValue(directory.resolve(".runtime/command-finalizations/smoke.json").toFile(), result);
        new CommandExecutionStore(mock(JdbcTemplate.class), mapper, workspace).recover();
    }
}
