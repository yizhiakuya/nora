package com.nora.datasource.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.nora.common.exception.BusinessException;

/**
 * 只读 SQL 守卫(architecture-v2.md 4.8.3 节工具输入层)。
 *
 * <p>在语句到达驱动前拒绝任何非单条 SELECT / SHOW / EXPLAIN 的语句。
 * 语句来自浏览器(以及后来的 LLM 工具),绝不可信任。
 */
public final class SqlGuard {

    /** 只读执行允许的起始动词。 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("select", "show", "explain");

    /** 绝不可作为首 token 出现的动词,纵深防御。 */
    private static final Set<String> MUTATING_PREFIXES = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "vacuum", "reindex", "call", "do", "merge");

    /** 语句体内的分号(撇开字符串字面量不谈,作为绊线足够)。 */
    private static final Pattern MULTIPLE_STATEMENTS = Pattern.compile(";\\s*\\S");

    private SqlGuard() {
    }

    /**
     * 校验 SQL 是单条只读语句。
     *
     * @param sql 原始 SQL 文本
     * @throws BusinessException 语句被拒时 400
     */
    public static void requireReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(400, "sql is required");
        }
        String trimmed = stripLeadingComments(sql).trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "sql is required");
        }
        // 检查前去掉尾部分号
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
            // SELECT ... INTO 会写数据
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
