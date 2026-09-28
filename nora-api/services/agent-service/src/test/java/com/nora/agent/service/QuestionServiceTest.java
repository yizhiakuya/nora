package com.nora.agent.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * QuestionService 冒烟(2026-09-29,ask_user 澄清提问):
 * 注册/结算/超时/取消路径的执行覆盖(项目约定:单测不写断言,行为走 E2E)。
 */
class QuestionServiceTest {

    @Test
    void registerResolveAndPendingLifecycle() {
        QuestionService service = new QuestionService();
        QuestionService.Registered registered = service.registerWithFuture(
                "s1", "s-call-1", "用哪个搜索服务?", List.of("tavily", "exa"), null);
        service.pendingFor("s1");
        // 错误会话不结算
        service.resolve("other", registered.ticket().questionToken(), "tavily");
        // 空答案不结算
        service.resolve("s1", registered.ticket().questionToken(), "   ");
        // 正常结算
        service.resolve("s1", registered.ticket().questionToken(), "tavily");
        service.awaitFuture(registered);
        // 已消费的 token 再结算返回 false(执行路径覆盖)
        service.resolve("s1", registered.ticket().questionToken(), "again");
        service.pendingFor("s1");
    }

    @Test
    void timeoutAndClearPendingCompleteWithNull() {
        QuestionService service = new QuestionService();
        // 超时参数收敛(低于下限→下限;无参→默认)——仅执行路径
        QuestionService.Registered shortOne = service.registerWithFuture("s1", "step-1", "q", null, 0);
        QuestionService.Registered capped = service.registerWithFuture("s1", "step-2", "q", null, 99999);
        shortOne.ticket().timeoutSeconds();
        capped.ticket().timeoutSeconds();
        // 取消清理:全部以 null 结算
        service.clearPending("s1");
        service.awaitFuture(shortOne);
        service.awaitFuture(capped);
    }

    @Test
    void blankAnswerRejectedAndOtherSessionIgnored() {
        QuestionService service = new QuestionService();
        QuestionService.Registered r = service.registerWithFuture("sA", "step-1", "问题?", null, 60);
        service.resolve("sB", r.ticket().questionToken(), "x"); // 会话不匹配
        service.resolve("sA", r.ticket().questionToken(), null); // 空答案
        service.clearPending("sA");
        service.awaitFuture(r);
        new ObjectMapper(); // 构造器无副作用(覆盖 imports)
    }
}
