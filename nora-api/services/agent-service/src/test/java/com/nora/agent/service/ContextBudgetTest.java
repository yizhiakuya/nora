package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ContextBudget 分层预算与 CJK 感知估算。 */
class ContextBudgetTest {

    @Test
    void defaultsWhenWindowMissing() {
        ContextBudget b = new ContextBudget(null);
        assertEquals(128_000L, b.window());
        assertEquals((long) (128_000 * 0.83), b.triggerTokens());
        assertEquals(12_800L, b.outputReserve()); // min(16k, 10%)
    }

    @Test
    void configuredWindowDrivesLayers() {
        ContextBudget b = new ContextBudget(200_000L);
        assertEquals(200_000L, b.window());
        assertEquals(16_000L, b.outputReserve()); // capped at 16k
        // 历史预算 = 触发线 - 输出预留 - 固定开销
        assertEquals((long) (200_000 * 0.83) - 16_000 - 5_000, b.historyBudgetTokens(5_000));
    }

    @Test
    void historyBudgetNeverBelowFloor() {
        ContextBudget b = new ContextBudget(8_000L); // 极小窗口
        assertTrue(b.historyBudgetTokens(999_999) >= 2_000); // 保底 2k
    }

    @Test
    void cjkAwareEstimation() {
        // 中文≈1 token/字;"上下文设计"5 字 + 封装 4 = 9
        assertEquals(9, ContextBudget.estimateTokens("上下文设计"));
        // ASCII≈1/4;"context window"14 字符 → 3 + 封装 4 = 7
        assertEquals(7, ContextBudget.estimateTokens("context window"));
        // 混合:2 中文 + 8 ASCII(2 tokens) + 封装 4
        assertEquals(8, ContextBudget.estimateTokens("上下 context"));
    }

    @Test
    void compactToolContentKeepsHeadAndTail() {
        String big = "x".repeat(2_000);
        String out = ChatOrchestrationService.compactToolContent(big);
        assertTrue(out.length() < 1_100);
        assertTrue(out.contains("已压缩"));
        // 短结果原样返回
        assertEquals("short", ChatOrchestrationService.compactToolContent("short"));
    }

    @Test
    void overflowDetectorMatchesRelayWording() {
        assertTrue(ChatOrchestrationService.isContextOverflow("上游 400: This model's maximum context length is 8192 tokens"));
        assertTrue(ChatOrchestrationService.isContextOverflow("context_length_exceeded"));
        assertTrue(ChatOrchestrationService.isContextOverflow("请求超过上下文长度限制"));
        assertFalse(ChatOrchestrationService.isContextOverflow("connection reset"));
        assertFalse(ChatOrchestrationService.isContextOverflow(null));
    }
}
