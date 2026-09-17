package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * 只读查询的结果,由 {@link DatasourceService#executeReadOnly(Long, String)} 返回。
 *
 * <p>值渲染为字符串(驱动格式),按 ACI 原则(architecture-v2.md 4.8.3 节)
 * 保持载荷可序列化且 LLM 友好;{@code null} 单元格以 {@code null} 条目透传,
 * 让调用方可标记。
 *
 * @param columns    结果列名(按序)
 * @param rows       与 {@code columns} 对齐的结果行;每格是字符串渲染值或 {@code null}
 * @param rowCount   返回行数(提供方截断后)
 * @param durationMs 查询执行挂钟耗时(毫秒)
 * @param truncated  提供方行数上限是否截了结果集;调用方必须如实展示——
 *                   静默截断会让模型把部分结果当完整(2026-09-05 浏览器实测发现)
 */
public record QueryResult(
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        long durationMs,
        boolean truncated) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 在 {@code truncated} 之前编写的调用方的兼容构造。 */
    public QueryResult(List<String> columns, List<List<String>> rows, int rowCount, long durationMs) {
        this(columns, rows, rowCount, durationMs, false);
    }
}
