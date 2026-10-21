package com.nora.agent.service;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

/** 一次流式模型轮次:转发的 content/reasoning + 累积的 tool_calls。 */
record StreamTurnResult(boolean failed, String errorMessage, String content,
                        String reasoning, AiMessage assistantMessage, List<ToolExecutionRequest> toolCalls,
                        ChatOrchestrationService.TokenUsage usage, EndState endState, String phase, Integer httpStatus) {
    enum EndState { COMPLETED, OUTPUT_LIMIT, INTERRUPTED, CONTENT_FILTER, INCOMPLETE, FAILED }

    StreamTurnResult(boolean failed, String errorMessage, String content, String reasoning,
                     AiMessage assistantMessage, List<ToolExecutionRequest> toolCalls, ChatOrchestrationService.TokenUsage usage,
                     EndState endState, String phase) {
        this(failed, errorMessage, content, reasoning, assistantMessage, toolCalls, usage, endState, phase, null);
    }

    StreamTurnResult(boolean failed, String errorMessage, String content, String reasoning,
                     AiMessage assistantMessage, List<ToolExecutionRequest> toolCalls, ChatOrchestrationService.TokenUsage usage) {
        this(failed, errorMessage, content, reasoning, assistantMessage, toolCalls, usage,
                failed ? EndState.FAILED : EndState.COMPLETED, null);
    }

    StreamTurnResult {
        if (endState != EndState.COMPLETED) {
            failed = true;
            if (errorMessage == null) errorMessage = endState.name();
        }
        if (!failed && content.isBlank() && toolCalls.isEmpty()) {
            failed = true;
            if (errorMessage == null) errorMessage = "Empty model response";
        }
    }

    boolean hasOnlyReasoning() {
        return content.isBlank() && toolCalls.isEmpty() && !reasoning.isBlank();
    }

    boolean retryable() {
        return failed && content.isBlank() && reasoning.isBlank() && toolCalls.isEmpty()
                && (endState == EndState.INTERRUPTED || endState == EndState.INCOMPLETE
                    || httpStatus != null && (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 && httpStatus <= 599));
    }

    boolean isFinalAnswer() {
        return !failed && toolCalls.isEmpty() && (phase == null || "final_answer".equals(phase));
    }
}
