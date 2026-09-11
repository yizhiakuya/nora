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
        Integer roundIndex,
        ContextInfo context
) {

    /** Convenience constructor for legacy call sites (no structured tool payload). */
    public ChatStepDto(String id, String type, String title, String detail, Long duration, String status) {
        this(id, type, title, detail, duration, status, null, null, null, null, null);
    }

    /** Convenience constructor for tool steps (no context payload). */
    public ChatStepDto(String id, String type, String title, String detail, Long duration, String status,
                       String toolName, StepInput input, StepResult result, Integer roundIndex) {
        this(id, type, title, detail, duration, status, toolName, input, result, roundIndex, null);
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

    /**
     * 上下文注入元数据(dsh 的自描述 source 模式):type=context 的步骤携带
     * {@code form} 声明信息形态,前端按 form 渲染;未知 form 前端降级为通用展示。
     *
     * @param form     信息形态:{@code instructions}(文件注入)/ {@code catalog}(条目目录)
     * @param kind     生产者标识(如 workspace-bootstrap / skill-catalog),前端无需白名单
     * @param files    注入文件清单(instructions 形态)
     * @param entries  目录条目( catalog 形态)
     * @param dailyNotes 日记清单(只列名不注入正文)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextInfo(
            String form,
            String kind,
            java.util.List<ContextFile> files,
            java.util.List<ContextEntry> entries,
            java.util.List<String> dailyNotes
    ) {
    }

    /** 一个注入文件:{@code bytes} 为实际注入字节数,truncated=被预算/长度截断,content=模型读到的正文。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextFile(String path, Integer bytes, Boolean truncated, Boolean missing, String content) {
    }

    /** 一条目录条目(技能目录等):名称 + 描述。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextEntry(String name, String description, String category) {
    }
}
