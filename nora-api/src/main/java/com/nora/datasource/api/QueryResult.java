package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * 数据库命令结果：结果行，或 rows_affected。
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

    public String toTsv() {
        StringBuilder out = new StringBuilder(String.join("\t", columns)).append('\n');
        for (List<String> row : rows) {
            out.append(row.stream().map(value -> value == null ? "NULL" : value)
                    .collect(java.util.stream.Collectors.joining("\t"))).append('\n');
        }
        return out.append('(').append(rowCount).append(" rows, ").append(durationMs).append("ms)")
                .append(truncated ? " [结果已截断]" : "").toString();
    }
}
