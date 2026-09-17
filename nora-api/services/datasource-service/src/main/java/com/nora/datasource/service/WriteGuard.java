package com.nora.datasource.service;

import java.util.Locale;
import java.util.Set;

import com.nora.common.exception.BusinessException;

/**
 * 审批门控 execute 端点的写语句守卫(与 {@link SqlGuard} 互补:那个只许读,
 * 这个只许写)。纵深防御——agent 侧 RiskClassifier 是建议性的,
 * 本层强制。
 */
public final class WriteGuard {

    private static final Set<String> WRITE_VERBS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "vacuum", "reindex", "call", "do", "merge");

    private WriteGuard() {
    }

    /**
     * @throws BusinessException 语句不是单条写时 400
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
