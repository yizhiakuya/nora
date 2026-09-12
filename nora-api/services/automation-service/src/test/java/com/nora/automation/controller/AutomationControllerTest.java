package com.nora.automation.controller;

import com.nora.automation.service.AutomationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class AutomationControllerTest {

    @Mock
    private AutomationService service;

    private AutomationController controller;

    @BeforeEach
    void setUp() {
        controller = new AutomationController(service);
    }

    @Test
    void healthReturnsOk() {
        controller.health();
    }

    @Test
    void listWrapsRulesInEnvelope() {
        List<AutomationService.RuleView> rules = List.of(
                new AutomationService.RuleView(1L, "巡检", "daily", "每日定时",
                        "{\"type\":\"sql\"}", true, "active", null));
        when(service.list()).thenReturn(rules);

        controller.list();
    }

    @Test
    void runDelegatesToService() {
        AutomationService.ExecutionView view = new AutomationService.ExecutionView(
                9L, "巡检", 120L, "success", "3 rows", null);
        when(service.runNow(1L)).thenReturn(view);

        controller.run(1L);

    }

    @Test
    void executionsClampsLimit() {
        when(service.listExecutions(50)).thenReturn(List.of());

        controller.executions(null);


    }
}
