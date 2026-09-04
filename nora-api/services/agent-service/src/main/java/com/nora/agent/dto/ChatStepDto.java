package com.nora.agent.dto;

/**
 * SSE {@code step} event payload, matching the frontend ChatStep contract
 * (agentApi.ts StepPayload): camelCase, duration accepts a string label or
 * milliseconds.
 *
 * @param id       step id, unique within one answer
 * @param type     {@code think} or {@code tool}
 * @param title    short label
 * @param detail   human-readable detail
 * @param duration wall-clock duration in milliseconds (null while running)
 * @param status   {@code pending} / {@code running} / {@code completed} / {@code failed}
 */
public record ChatStepDto(
        String id,
        String type,
        String title,
        String detail,
        Long duration,
        String status
) {
}
