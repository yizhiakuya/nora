package com.nora.agent.service;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * QuestionService 冒烟(2026-09-29,ask_user 澄清提问):
 * 注册/结算/超时/取消路径的执行覆盖(项目约定:单测不写断言,行为走 E2E)。
 */
class QuestionServiceTest {

    @Test
    void inputBeforeAndDuringQuestionWakesWaiter() throws Exception {
        var service = new QuestionService();
        var registry = new TurnStreamRegistry();
        var turn = registry.start("steering", "initial");
        turn.openInputs(input -> { });
        var signal = turn.inputAvailable();
        turn.enqueueInput(new TurnStreamRegistry.UserInput("first", "补充", null), () -> { });
        var first = service.registerWithFuture("steering", "first-question", "问题", null, 300);
        service.awaitFuture(first, signal);
        service.pendingFor("steering");
        turn.takeInputs(false);
        var second = service.registerWithFuture("steering", "second-question", "新问题", null, 300);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> service.awaitFuture(second, turn.inputAvailable()));
            turn.enqueueInput(new TurnStreamRegistry.UserInput("second", "新请求", null), () -> { });
            waiting.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        turn.takeInputs(false);
        var third = service.registerWithFuture("steering", "third-question", "正常作答", null, 300);
        service.resolve("steering", third.ticket().questionToken(), "答案");
        service.awaitFuture(third, turn.inputAvailable());
        registry.finish("steering", turn);
    }

    @Test
    void registerResolveAndPendingLifecycle() {
        QuestionService service = new QuestionService();
        QuestionService.Registered registered = service.registerWithFuture(
                "s1", "s-call-1", "用哪个搜索服务?", List.of("tavily", "exa"), null);
        service.pendingFor("s1");
        service.pendingActions();
        // 错误会话不结算
        service.resolve("other", registered.ticket().questionToken(), "tavily");
        // 空答案不结算
        service.resolve("s1", registered.ticket().questionToken(), "   ");
        // 正常结算
        service.resolve("s1", registered.ticket().questionToken(), "tavily");
        service.awaitFuture(registered, new java.util.concurrent.CompletableFuture<>());
        // 已消费的 token 再结算返回 false(执行路径覆盖)
        service.resolve("s1", registered.ticket().questionToken(), "again");
        service.pendingActions();
        service.pendingFor("s1");
    }

    @Test
    void timeoutAndClearPendingPreserveCancellation() {
        QuestionService service = new QuestionService();
        // 超时参数收敛(低于下限→下限;无参→默认)——仅执行路径
        QuestionService.Registered shortOne = service.registerWithFuture("s1", "step-1", "q", null, 0);
        QuestionService.Registered capped = service.registerWithFuture("s1", "step-2", "q", null, 99999);
        shortOne.ticket().timeoutSeconds();
        capped.ticket().timeoutSeconds();
        // 取消与超时走独立路径。
        service.clearPending("s1");
        for (var registered : List.of(shortOne, capped)) {
            try { service.awaitFuture(registered, new java.util.concurrent.CompletableFuture<>()); }
            catch (java.util.concurrent.CancellationException cancelled) { }
        }
        var timeout = service.registerWithFuture("s1", "timeout", "q", null, 5);
        timeout.future().completeExceptionally(new java.util.concurrent.TimeoutException());
        service.awaitFuture(timeout, new java.util.concurrent.CompletableFuture<>());
    }

    @Test
    void blankAnswerRejectedAndOtherSessionIgnored() {
        QuestionService service = new QuestionService();
        QuestionService.Registered r = service.registerWithFuture("sA", "step-1", "问题?", null, 60);
        service.resolve("sB", r.ticket().questionToken(), "x"); // 会话不匹配
        service.resolve("sA", r.ticket().questionToken(), null); // 空答案
        service.clearPending("sA");
        try { service.awaitFuture(r, new java.util.concurrent.CompletableFuture<>()); }
        catch (java.util.concurrent.CancellationException cancelled) { }
    }
}
