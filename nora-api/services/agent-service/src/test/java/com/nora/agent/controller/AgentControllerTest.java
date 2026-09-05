package com.nora.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit test: instantiates the controller directly, no Spring context, no Nacos.
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    private ChatOrchestrationService orchestrationService;

    @Mock
    private ChatStoreService chatStoreService;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(orchestrationService, chatStoreService, new ObjectMapper());
    }

    @Test
    void healthReturnsOk() {
        assertEquals("ok", controller.health());
    }

    @Test
    void sendMessageRejectsBlankContent() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.sendMessage("s1", new AgentController.MessageRequest("  ", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> controller.sendMessage("s1", new AgentController.MessageRequest(null, null, null)));
        verify(chatStoreService, never()).ensureSession(anyString(), anyString());
    }

    @Test
    void messagesDelegatesToStore() {
        when(chatStoreService.loadMessages("s1")).thenReturn(List.of());
        assertEquals(List.of(), controller.messages("s1"));
        verify(chatStoreService).loadMessages("s1");
    }

    @Test
    void sendMessageReturnsEmitterWithoutAwaitingTheTurn() {
        // The chat turn runs on the controller's executor; the HTTP thread
        // must get the emitter back immediately, not block on the LLM.
        SseEmitter emitter = controller.sendMessage("s1",
                new AgentController.MessageRequest("hello", "test-model", null));

        assertEquals(SseEmitter.class, emitter.getClass());
    }
}
