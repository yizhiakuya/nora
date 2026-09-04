package com.nora.automation.api;

import java.io.Serializable;

import java.time.Instant;

/**
 * Result of a single automation rule execution, returned by
 * {@link AutomationService#run(Long)}.
 *
 * <p>{@code detail} follows the ACI principle (architecture-v2.md section 4.8.3):
 * it carries an LLM-friendly structured summary, not raw executor output.
 *
 * @param id         execution id
 * @param ruleId     id of the rule that was executed
 * @param status     execution status (e.g. {@code running}, {@code success}, {@code failed})
 * @param durationMs wall-clock execution duration in milliseconds
 * @param detail     human-readable outcome summary
 * @param startedAt  execution start time
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
