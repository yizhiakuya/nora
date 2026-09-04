package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only SQL guard (architecture-v2.md section 4.8.3 tool-input layer).
 *
 * <p>Rejects any statement that is not a single SELECT / SHOW / EXPLAIN
 * before it reaches the driver. Statements come from the browser (and later
 * the LLM tool) and must never be trusted.
 */
public final class SqlGuard {

    /** Leading verbs allowed for read-only execution. */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("select", "show", "explain");

    /** Verbs that must never appear as the first token, defense in depth. */
    private static final Set<String> MUTATING_PREFIXES = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "vacuum", "reindex", "call", "do", "merge");

    /** Semicolon inside a statement body (string literal comments aside, good enough for a tripwire). */
    private static final Pattern MULTIPLE_STATEMENTS = Pattern.compile(";\\s*\\S");

    private SqlGuard() {
    }

    /**
     * Validates that the SQL is a single read-only statement.
     *
     * @param sql raw SQL text
     * @throws BusinessException 400 when the statement is rejected
     */
    public static void requireReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(400, "sql is required");
        }
        String trimmed = stripLeadingComments(sql).trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "sql is required");
        }
        // strip trailing semicolon before inspection
        String body = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (body.contains(";")) {
            throw new BusinessException(400, "multiple statements are not allowed");
        }
        if (MULTIPLE_STATEMENTS.matcher(trimmed).find()) {
            throw new BusinessException(400, "multiple statements are not allowed");
        }
        String firstWord = firstWord(body);
        if (MUTATING_PREFIXES.contains(firstWord)) {
            throw new BusinessException(400, "only read-only statements (SELECT/SHOW/EXPLAIN) are allowed");
        }
        if (!ALLOWED_PREFIXES.contains(firstWord)) {
            throw new BusinessException(400, "only read-only statements (SELECT/SHOW/EXPLAIN) are allowed");
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("into") && firstWord.equals("select")) {
            // SELECT ... INTO writes
            throw new BusinessException(400, "SELECT INTO is not allowed");
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

    private static String stripLeadingComments(String sql) {
        String s = sql;
        while (true) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl < 0) {
                    return "";
                }
                s = s.substring(nl + 1);
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) {
                    return "";
                }
                s = s.substring(end + 2);
            } else {
                return s;
            }
        }
    }
}
