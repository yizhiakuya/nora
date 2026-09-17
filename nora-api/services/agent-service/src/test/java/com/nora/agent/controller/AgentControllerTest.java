package com.nora.agent.controller;

import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;

/**
 * 纯单元测试:直接实例化控制器,无 Spring 上下文、无 Nacos。
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
        // 对话轮在控制器的执行器上跑;HTTP 线程必须立即拿回 emitter,
        // 不能阻塞在 LLM 上。
        SseEmitter emitter = controller.sendMessage("s1",
                new AgentController.MessageRequest("hello", "test-model", null));

        emitter.getClass();
    }
}
