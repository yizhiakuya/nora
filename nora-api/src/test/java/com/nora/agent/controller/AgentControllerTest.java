package com.nora.agent.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nora.agent.dto.ChatRequest;
import com.nora.agent.service.*;

class AgentControllerTest {
    @Test
    void delegatesSessionAndRunOperations() {
        var store = mock(ChatStoreService.class);
        var runs = mock(ChatRunService.class);
        var request = new ChatRequest("hello", "test-model", null, null, null, null, null, false, null);
        when(runs.stream("s1", request, null)).thenReturn(new SseEmitter());
        when(store.loadMessages("s1")).thenReturn(List.of());
        var controller = new AgentController(store, mock(ApprovalService.class), mock(QuestionService.class),
                new TurnStreamRegistry(), mock(SessionProjectService.class), mock(ChatInputQueueService.class), runs);
        controller.health();
        controller.messages("s1");
        controller.sendMessage("s1", request).getTimeout();
        controller.cancelTurn("s1");
        when(runs.withIdleSession(eq("s1"), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        controller.deleteSession("s1");
        controller.deleteSession("s1");
        try { controller.sendMessage("s1", new ChatRequest(" ", null, null, null, null, null, null, false, null)); }
        catch (IllegalArgumentException expected) { }
    }
}
