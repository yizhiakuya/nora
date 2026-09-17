package com.nora.automation.api;

import java.io.Serializable;
import java.time.Instant;

/**
 * 单次自动化规则执行的结果,由 {@link AutomationService#run(Long)} 返回。
 *
 * <p>{@code detail} 遵循 ACI 原则(architecture-v2.md 4.8.3 节):
 * 携带 LLM 友好的结构化摘要,不是执行器原始输出。
 *
 * @param id         执行 id
 * @param ruleId     被执行的规则 id
 * @param status     执行状态(如 {@code running}、{@code success}、{@code failed})
 * @param durationMs 挂钟执行耗时(毫秒)
 * @param detail     人类可读结果摘要
 * @param startedAt  执行开始时间
 */
public record ExecutionRecord(
        Long id,
        Long ruleId,
        String status,
        Long durationMs,
        String detail,
        Instant startedAt) implements Serializable {

    private static final long serialVersionUID = 1L;
}
