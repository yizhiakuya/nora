package com.nora.agent.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nora.agent.dto.ChatRequest;
import com.nora.common.exception.BusinessException;

class ChatRunServiceTest {
    @Test
    void ownsSessionUntilWorkerExitsAndCompletesTheResult() throws Exception {
        var runner = mock(ChatTurnRunner.class);
        var queue = mock(ChatInputQueueService.class);
        when(queue.view("s1")).thenReturn(new ChatInputQueueService.View(List.of(), false, null));
        var settings = mock(AppSettingStore.class);
        when(settings.raw("agent")).thenReturn(Map.of("permissionMode", "full"));
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        when(runner.runChatTurn(anyString(), any(), nullable(String.class), any(), anyString(), any()))
                .thenAnswer(call -> {
                    entered.countDown();
                    try { exit.await(10, TimeUnit.SECONDS); } catch (InterruptedException expected) { }
                    return new ChatRunService.Result(call.getArgument(4), "s1", "cancelled", "", null);
                });
        var runs = new ChatRunService(runner, mock(ChatStoreService.class), settings, queue,
                new TurnStreamRegistry(), new TurnCancellation(), new ApprovalService(), mock(QuestionService.class), mock(TerminalService.class));
        try {
            var handle = runs.start("s1", new ChatRequest("hello", null, null, null, null, null, null, false, null));
            entered.await(10, TimeUnit.SECONDS);
            try { runs.start("s1", new ChatRequest("duplicate", null, null, null, null, null, null, false, null)); }
            catch (BusinessException expected) { }
            runs.cancel("s1");
            exit.countDown();
            handle.completion().get(10, TimeUnit.SECONDS).status();
            runs.withIdleSession("s1", () -> "idle");
        } finally { exit.countDown(); runs.shutdown(); }
    }
}
