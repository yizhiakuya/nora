package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * A table (with its columns) inside a {@link SchemaSnapshot}.
 *
 * @param name    table name
 * @param columns ordered column list; never {@code null}, may be empty
 */
public record DbTable(
        String name,
        List<Column> columns) implements Serializable {

    private static final long serialVersionUID = 1L;
}
