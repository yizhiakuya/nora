package com.nora.agent.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.ContextCheckpointDto;

class ChatRunFinalizerTest {
    @TempDir Path directory;

    @Test
    void reloadsAndCommitsPendingResult() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = mock(ChatStoreService.class);
        doThrow(new IllegalStateException("Database unavailable")).doNothing().when(store).completeRun(
                anyString(), anyString(), anyString(), anyList(), anyList(), any(), any(), any(), any(),
                anyString(), anyString(), any(), anyBoolean(), any(), anyBoolean());
        var workspace = new AgentWorkspaceService(directory.toString());
        var finalizer = new ChatRunFinalizer(store, mapper, workspace);
        var checkpoint = new ContextCheckpointDto("boundary", "summary", "archive.md", 100, null,
                10, 5, java.time.LocalDateTime.now(), "run", 2);
        var result = new ChatRunFinalizer.FinalResult(UUID.randomUUID().toString().substring(0, 8), "session", "answer",
                List.of(new ChatStepDto("tool", "tool", "command", "result", 1L, "completed", "run_sh",
                        null, null, 1)), List.of(), 1L, 10, 128000L, null, "completed", "completed",
                null, false, checkpoint, false);
        try { finalizer.complete(result); } catch (IllegalStateException expected) { }
        var restarted = new ChatRunFinalizer(store, mapper, workspace);
        restarted.hasPending("session");
        restarted.pendingRunIds();
        restarted.retryPending();
        restarted.reconcileSession("session");
    }

    @Test
    void commitsCompactionResult() throws Exception {
        var finalizer = new ChatRunFinalizer(mock(ChatStoreService.class), new ObjectMapper(),
                new AgentWorkspaceService(directory.toString()));
        finalizer.complete(new ChatRunFinalizer.FinalResult("compact-" + UUID.randomUUID(), "session", "",
                List.of(), List.of(), null, null, null, null, "cancelled", "cancelled", null, false, null, true));
    }

    @Test
    void commitsCancelledResultOnInterruptedThread() throws Exception {
        var finalizer = new ChatRunFinalizer(mock(ChatStoreService.class), new ObjectMapper(),
                new AgentWorkspaceService(directory.toString()));
        Thread.currentThread().interrupt();
        try {
            finalizer.complete(new ChatRunFinalizer.FinalResult("cancel-" + UUID.randomUUID(), "session", "",
                    List.of(), List.of(), 1L, null, null, null, "cancelled", "cancelled", null, false, null, false));
        } finally {
            Thread.interrupted();
        }
    }
}
