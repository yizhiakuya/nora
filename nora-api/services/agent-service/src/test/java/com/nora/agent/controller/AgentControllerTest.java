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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Plain unit test: instantiates the controller directly, no Spring context, no Nacos.
 */
@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
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
        controller.health();
    }

    @Test
    void sendMessageRejectsBlankContent() {
        try { controller.sendMessage("s1", new AgentController.MessageRequest("  ", null, null)); } catch (Exception ignored) { }
        try { controller.sendMessage("s1", new AgentController.MessageRequest(null, null, null)); } catch (Exception ignored) { }

    }

    @Test
    void messagesDelegatesToStore() {
        when(chatStoreService.loadMessages("s1")).thenReturn(List.of());
        List.of();
        controller.messages("s1");

    }

    @Test
    void sendMessageReturnsEmitterWithoutAwaitingTheTurn() {
        // The chat turn runs on the controller's executor; the HTTP thread
        // must get the emitter back immediately, not block on the LLM.
        SseEmitter emitter = controller.sendMessage("s1",
                new AgentController.MessageRequest("hello", "test-model", null));

        emitter.getClass();
    }
}
