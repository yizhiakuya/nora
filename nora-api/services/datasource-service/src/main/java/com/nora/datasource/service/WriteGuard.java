package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * Write-statement guard for the approval-gated execute endpoint
 * (complement of {@link SqlGuard}: that one allows only reads, this one
 * only writes). Defense in depth — the agent-side RiskClassifier is
 * advisory, this layer enforces.
 */
public final class WriteGuard {

    private static final Set<String> WRITE_VERBS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "vacuum", "reindex", "call", "do", "merge");

    private WriteGuard() {
    }

    /**
     * @throws BusinessException 400 when the statement is not a single write
     */
    public static void requireWrite(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(400, "sql is required");
        }
        String trimmed = sql.trim();
        String body = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (body.contains(";")) {
            throw new BusinessException(400, "multiple statements are not allowed");
        }
        String first = firstWord(body);
        if (!WRITE_VERBS.contains(first)) {
            throw new BusinessException(400, "only write statements (INSERT/UPDATE/DELETE/DDL) are allowed here");
        }
    }

    private static String firstWord(String sql) {
        int i = 0;
        while (i < sql.length() && !Character.isLetter(sql.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < sql.length() && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toLowerCase(Locale.ROOT);
    }
}
