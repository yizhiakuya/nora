package com.nora.agent.service;

import org.springframework.stereotype.Component;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;

@Component
class QuestionTools {
    private final QuestionService questions;
    private final TurnStreamRegistry streams;

    QuestionTools(QuestionService questions, TurnStreamRegistry streams) { this.questions = questions; this.streams = streams; }

    @Tool(name = "ask_user", value = "缺少完成任务必须的信息时向用户提问并等待回答。同一轮继续执行，选项可省略；无人值守任务不能提问。")
    public ChatToolExecutor.ToolOutcome ask(@P(name = "input") ToolInputs.Question input, InvocationParameters invocation) {
        var context = ToolCallContext.from(invocation);
        if (context.unattended() || context.sessionId() == null) {
            return ChatToolExecutor.textOutcome("ERROR: 无人值守通道没有用户可作答。请基于合理假设继续，或列出待确认问题", "无法提问");
        }
        if (input.question() == null || input.question().isBlank()) {
            return ChatToolExecutor.textOutcome("ERROR: ask_user 需要非空 question 参数", "缺少 question 参数");
        }
        var turn = streams.get(context.sessionId());
        if (turn == null || turn.isFinished()) throw new java.util.concurrent.CancellationException("question turn ended");
        // 先捕获输入信号，注册问题前到达的插入也必须唤醒等待。
        var inputAvailable = turn.inputAvailable();
        var options = input.options() == null ? null : input.options().stream()
                .filter(option -> option != null && !option.isBlank()).toList();
        var registered = questions.registerWithFuture(context.sessionId(), context.stepId(),
                Texts.abbreviate(input.question(), 2000), options, input.timeoutSeconds());
        context.events().questionRequired(registered.ticket());
        var answer = questions.awaitFuture(registered, inputAvailable);
        if (answer.superseded()) return ChatToolExecutor.textOutcome(
                "用户已插入新请求，结束本次提问等待；原问题尚未作答。请先处理随后提供的新请求及引用，仍缺少必要信息时再提问，不要假定用户已授权操作。", "已转入插入请求");
        return answer.text() == null
                ? ChatToolExecutor.textOutcome("ERROR: 用户未在时限内回答。请基于合理假设继续；必须的信息留待用户确认", "用户未回答(超时)")
                : ChatToolExecutor.textOutcome("用户回答: " + answer.text(), "用户已回答");
    }
}
