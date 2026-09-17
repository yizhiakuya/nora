package com.nora.agent.api;

/**
 * agent 对话回答的一条流式步骤(SSE 事件载荷)。
 *
 * <p>agent-service 在 ReAct 循环运行时发出:每个推理轮一个 THINK 步骤、
 * 每次工具调用一个 TOOL 步骤,各自从 {@link ChatStepStatus#PENDING} 或
 * {@code RUNNING} 演进到终态。网关经 SSE 转发给前端
 * (architecture-v2.md 4.2 节)。</p>
 *
 * @param id         步骤 id,一次回答内唯一
 * @param type       步骤种类(推理 vs 工具调用)
 * @param title      短标签(如 {@code Searching docs…})
 * @param detail     人/LLM 可读细节;{@code status} 为 {@code FAILED} 时携带错误消息
 * @param durationMs 步骤挂钟耗时,running 时 null
 * @param status     步骤当前生命周期状态
 */
public record ChatStepEvent(
        String id,
        ChatStepType type,
        String title,
        String detail,
        Long durationMs,
        ChatStepStatus status) {
}
