package com.nora.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE {@code step} event payload, matching the frontend ChatStep contract
 * (agentApi.ts StepPayload): camelCase, duration accepts a string label or
 * milliseconds.
 *
 * <p>Structured tool fields (toolName/input/result) follow the harness
 * research (2026-09): a tool step carries its parsed arguments and a typed
 * result instead of one flattened detail string; {@code detail} stays as a
 * human-readable summary for the timeline and persistence.
 *
 * @param id       step id, unique within one answer
 * @param type     {@code think} or {@code tool}
 * @param title    short label
 * @param detail   human-readable summary line
 * @param duration wall-clock duration in milliseconds (null while running)
 * @param status   {@code pending} / {@code running} / {@code completed} / {@code failed} / {@code declined}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStepDto(
        String id,
        String type,
        String title,
        String detail,
        Long duration,
        String status,
        String toolName,
        StepInput input,
        StepResult result,
        Integer roundIndex
) {

    /** Convenience constructor for legacy call sites (no structured tool payload). */
    public ChatStepDto(String id, String type, String title, String detail, Long duration, String status) {
        this(id, type, title, detail, duration, status, null, null, null, null);
    }

    /**
     * Parsed tool arguments, rendered per-tool by the frontend.
     *
     * @param target  manage_datasource / manage_service 的操作对象(数据源名/id、
     *                纳管源名/id);null 表示其余工具
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepInput(String sql, String service, Integer limit, String target) {

        /** Back-compat constructor for existing call sites (no target). */
        public StepInput(String sql, String service, Integer limit) {
            this(sql, service, limit, null);
        }
    }

    /**
     * Structured tool result.
     *
     * @param content   full tool output fed to the model (bounded)
     * @param summary   one-line human summary (e.g. "3 rows in 12ms")
     * @param rowCount  row count for SQL results
     * @param truncated whether {@code content} was cut to the size budget
     * @param error     machine-readable failure reason when status is failed/declined
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepResult(
            String content,
            String summary,
            Integer rowCount,
            Integer lineCount,
            Boolean truncated,
            String error
    ) {
    }
}
