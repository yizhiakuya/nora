package com.nora.datasource.api;

import java.io.Serializable;

/**
 * {@link DbTable} 中的单个表列。
 *
 * @param name         列名
 * @param type         驱动报告的 JDBC 类型名(如 {@code varchar}、{@code int8})
 * @param comment      列注释(JDBC REMARKS);数据库没有时为空
 * @param nullable     列是否接受 NULL
 * @param primaryKey   列是否参与表主键
 * @param defaultValue 驱动报告的默认表达式(COLUMN_DEF);可为 {@code null}
 */
public record Column(
        String name,
        String type,
        String comment,
        boolean nullable,
        boolean primaryKey,
        String defaultValue) implements Serializable {

    private static final long serialVersionUID = 1L;
}
