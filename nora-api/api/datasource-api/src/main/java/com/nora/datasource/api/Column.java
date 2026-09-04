package com.nora.datasource.api;

import java.io.Serializable;

/**
 * A single table column in a {@link DbTable}.
 *
 * @param name column name
 * @param type JDBC type name as reported by the driver (e.g. {@code varchar}, {@code int8})
 */
public record Column(
        String name,
        String type) implements Serializable {

    private static final long serialVersionUID = 1L;
}
