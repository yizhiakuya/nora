package com.nora.datasource.api;

import java.io.Serializable;

/**
 * A single table column in a {@link DbTable}.
 *
 * @param name         column name
 * @param type         JDBC type name as reported by the driver (e.g. {@code varchar}, {@code int8})
 * @param comment      column comment (JDBC REMARKS); empty when the database has none
 * @param nullable     whether the column accepts NULL
 * @param primaryKey   whether the column participates in the table primary key
 * @param defaultValue default expression as reported by the driver (COLUMN_DEF); may be {@code null}
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
