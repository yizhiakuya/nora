package com.nora.datasource.api;

import java.io.Serializable;
import java.util.List;

/**
 * Result of a read-only query, returned by
 * {@link DatasourceService#executeReadOnly(Long, String)}.
 *
 * <p>Values are rendered as strings (driver formatting), which keeps the
 * payload serializable and LLM-friendly per the ACI principle
 * (architecture-v2.md section 4.8.3); {@code null} cell values are passed
 * through as {@code null} entries so the caller can mark them.
 *
 * @param columns   result column names in order
 * @param rows      result rows aligned with {@code columns}; each cell is the string-rendered value or {@code null}
 * @param rowCount  number of returned rows (after provider-side truncation)
 * @param durationMs query execution wall-clock duration in milliseconds
 */
public record QueryResult(
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        long durationMs) implements Serializable {

    private static final long serialVersionUID = 1L;
}
