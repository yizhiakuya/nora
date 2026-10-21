package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * {@link SchemaSnapshot} 中的一张表(及其列)。
 *
 * @param schema  所属 schema/catalog(如 pg "public");无 schema 的引擎可为 {@code null}
 * @param name    表名
 * @param comment 表注释(JDBC REMARKS);数据库没有时为空
 * @param columns 有序列列表;绝不 {@code null},可为空
 */
public record DbTable(
        String schema,
        String name,
        String comment,
        List<Column> columns) implements Serializable {

    private static final long serialVersionUID = 1L;
}
