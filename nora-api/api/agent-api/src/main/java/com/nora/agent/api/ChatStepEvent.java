package com.nora.agent.api;

/**
 * One streamed step of an agent chat answer (SSE event payload).
 *
 * <p>agent-service emits these while the ReAct loop runs: a THINK step per
 * reasoning turn and a TOOL step per tool invocation, each progressing from
 * {@link ChatStepStatus#PENDING} or {@code RUNNING} to a terminal status.
 * The gateway forwards them to the frontend over SSE (architecture-v2.md
 * section 4.2).</p>
 *
 * @param id         step id, unique within one chat answer
 * @param type       step kind (reasoning vs. tool call)
 * @param title      short label (e.g. {@code Searching docs…})
 * @param detail     human/LLM-readable detail; carries the error message when {@code status} is {@code FAILED}
 * @param durationMs wall-clock duration of the step, null while running
 * @param status     current lifecycle status of the step
 */
public record ChatStepEvent(
        String id,
        ChatStepType type,
        String title,
        String detail,
        Long durationMs,
        ChatStepStatus status) {
}
