package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * A table (with its columns) inside a {@link SchemaSnapshot}.
 *
 * @param schema  containing schema/catalog (e.g. pg "public"); may be {@code null}
 *                for engines without schemas
 * @param name    table name
 * @param comment table comment (JDBC REMARKS); empty when the database has none
 * @param columns ordered column list; never {@code null}, may be empty
 */
public record DbTable(
        String schema,
        String name,
        String comment,
        List<Column> columns) implements Serializable {

    private static final long serialVersionUID = 1L;
}
