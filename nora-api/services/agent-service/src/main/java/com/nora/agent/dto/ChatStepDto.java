package com.nora.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE {@code step} 事件载荷,匹配前端 ChatStep 契约(agentApi.ts StepPayload):
 * 驼峰;duration 接受字符串标签或毫秒。
 *
 * <p>结构化工具字段(toolName/input/result)遵循 harness 调研(2026-09):
 * 工具步骤携带解析后的参数与带类型结果,而不是一条压平的 detail 字符串;
 * {@code detail} 保留为时间线与持久化用的人类可读摘要。
 *
 * @param id       步骤 id,一次回答内唯一
 * @param type     {@code think} 或 {@code tool}
 * @param title    短标签
 * @param detail   人类可读摘要行
 * @param duration 挂钟耗时毫秒(running 时为 null)
 * @param status   状态:{@code pending} / {@code running} / {@code completed} / {@code failed} / {@code declined}
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
        ContextInfo context,
        StepProgress progress
) {

    /** 遗留调用点的便捷构造(无结构化工具载荷)。 */
    public ChatStepDto(String id, String type, String title, String detail, Long duration, String status) {
        this(id, type, title, detail, duration, status, null, null, null, null, null, null);
    }

    /** 工具步骤的便捷构造(无 context/progress 载荷)。 */
    public ChatStepDto(String id, String type, String title, String detail, Long duration, String status,
                       String toolName, StepInput input, StepResult result, Integer roundIndex) {
        this(id, type, title, detail, duration, status, toolName, input, result, roundIndex, null, null);
    }

    /** 带 context 的构造(进度为空)。 */
    public ChatStepDto(String id, String type, String title, String detail, Long duration, String status,
                       String toolName, StepInput input, StepResult result, Integer roundIndex,
                       ContextInfo context) {
        this(id, type, title, detail, duration, status, toolName, input, result, roundIndex, context, null);
    }

    /**
     * 批量任务的实时进度(2026-09-17,fetch_media 等):随 running 步骤原地刷新。
     *
     * <p>为什么放进步骤事件而不是独立事件类型:步骤按 id 原地替换已是全链路
     * 既有语义(SSE 直发 / TurnStreamRegistry 重连回放 / 前端按 id 合并),
     * 进度搭这班车即天然获得"断线重连看到最新进度"——无需新协议。
     *
     * @param phase        阶段:{@code listing}(获取清单)/ {@code downloading}
     * @param done         已完成文件数(含跳过/失败)
     * @param total        总文件数
     * @param currentIndex 当前正在下载的文件序号(1-based;并发时=最近开始的那个)
     * @param currentFile  当前正在下载的文件名
     * @param active       并行下载中的文件数
     * @param bytesDone    已传输字节(完成文件全量 + 在途文件已读部分)
     * @param bytesTotal   清单声明的总字节(sizeBytes 求和;含未知大小时偏小)
     * @param bytesPerSec  当前速率(6s 滑窗;样本不足时为 null)
     * @param etaSeconds   预计剩余秒数(字节速率优先,退化按文件均速;null=未知)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepProgress(
            String phase,
            Integer done,
            Integer total,
            Integer currentIndex,
            String currentFile,
            Integer active,
            Long bytesDone,
            Long bytesTotal,
            Long bytesPerSec,
            Long etaSeconds
    ) {
    }

    /**
     * 解析后的工具参数,由前端按工具渲染。
     *
     * @param target  manage_datasource / manage_service 的操作对象(数据源名/id、
     *                纳管源名/id);null 表示其余工具
     * @param rawArgs 脱敏后的原始参数 JSON(scrubArgsForLog)。仅用于跨轮
     *                历史重建(wire 层 tool_calls.arguments 需要完整原参),
     *                前端展示仍用上面的类型化字段;旧数据为 null 时重建退化为
     *                文本历史(兼容)。不存凭据明文。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepInput(String sql, String service, Integer limit, String target, String rawArgs) {

        /** 既有调用点的兼容构造(无 target)。 */
        public StepInput(String sql, String service, Integer limit) {
            this(sql, service, limit, null, null);
        }

        /** 兼容构造(有 target,无 raw args)。 */
        public StepInput(String sql, String service, Integer limit, String target) {
            this(sql, service, limit, target, null);
        }
    }

    /**
     * 结构化工具结果。
     *
     * @param content   喂给模型的完整工具输出(有界)
     * @param summary   一行人类摘要(如 "3 rows in 12ms")
     * @param rowCount  SQL 结果的行数
     * @param truncated {@code content} 是否被裁到大小预算
     * @param error     status 为 failed/declined 时的机器可读失败原因
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepResult(
            String content,
            String summary,
            Integer rowCount,
            Integer lineCount,
            Boolean truncated,
            String error,
            java.util.List<ViewerFile> files,
            String focusTarget,
            java.util.List<com.nora.agent.service.ViewerService.FileError> fileErrors
    ) {
        public StepResult(String content, String summary, Integer rowCount, Integer lineCount,
                          Boolean truncated, String error) {
            this(content, summary, rowCount, lineCount, truncated, error, null, null, null);
        }
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
