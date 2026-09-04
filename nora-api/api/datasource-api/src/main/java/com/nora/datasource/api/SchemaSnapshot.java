package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * Full schema of a connected database, returned by
 * {@link DatasourceService#schema(Long)}.
 *
 * @param tables all tables of the connection in driver order; never {@code null}, may be empty
 */
public record SchemaSnapshot(
        List<DbTable> tables) implements Serializable {

    private static final long serialVersionUID = 1L;
}
