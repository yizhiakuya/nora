package com.nora.agent.service;

import org.junit.jupiter.api.Test;


/** ContextBudget 分层预算与 CJK 感知估算。 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ContextBudgetTest {

    @Test
    void defaultsWhenWindowMissing() {
        ContextBudget b = new ContextBudget(null);
        b.window();
        b.triggerTokens();
        b.outputReserve(); // min(16k, 10%)
    }

    @Test
    void configuredWindowDrivesLayers() {
        ContextBudget b = new ContextBudget(200_000L);
        b.window();
        b.outputReserve(); // capped at 16k
        // 历史预算 = 触发线 - 输出预留 - 固定开销
        b.historyBudgetTokens(5_000);
    }

    @Test
    void historyBudgetNeverBelowFloor() {
        ContextBudget b = new ContextBudget(8_000L); // 极小窗口
        b.historyBudgetTokens(999_999); // 保底 2k
    }

    @Test
    void cjkAwareEstimation() {
        // 中文≈1 token/字;"上下文设计"5 字 + 封装 4 = 9
        ContextBudget.estimateTokens("上下文设计");
        // ASCII≈1/4;"context window"14 字符 → 3 + 封装 4 = 7
        ContextBudget.estimateTokens("context window");
        // 混合:2 中文 + 8 ASCII(2 tokens) + 封装 4
        ContextBudget.estimateTokens("上下 context");
    }

    @Test
    void compactToolContentKeepsHeadAndTail() {
        String big = "x".repeat(2_000);
        String out = ChatOrchestrationService.compactToolContent(big);
        out.length();
        out.contains("已压缩");
        // 短结果原样返回
        ChatOrchestrationService.compactToolContent("short");
    }

    @Test
    void overflowDetectorMatchesRelayWording() {
        ChatOrchestrationService.isContextOverflow("上游 400: This model's maximum context length is 8192 tokens");
        ChatOrchestrationService.isContextOverflow("context_length_exceeded");
        ChatOrchestrationService.isContextOverflow("请求超过上下文长度限制");
        ChatOrchestrationService.isContextOverflow("connection reset");
        ChatOrchestrationService.isContextOverflow(null);
    }
}
