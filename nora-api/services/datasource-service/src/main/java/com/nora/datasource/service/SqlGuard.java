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

    /**
     * EXPLAIN ANALYZE(含 {@code EXPLAIN (ANALYZE, ...)} 形式)会**真正执行**
     * 被包裹的语句——PostgreSQL/MySQL 语义一致。写语句经此绕过只读门
     * (2026-09-19 审查发现:只读通道可借 EXPLAIN ANALYZE INSERT/UPDATE/DELETE
     * 执行写操作)。不带 ANALYZE 的 EXPLAIN 只做计划,允许;
     * {@code EXPLAIN ANALYZE SELECT} 执行的是只读语句,与 SELECT 同权限,允许。
     */
    private static final Pattern EXPLAIN_PREFIX = Pattern.compile("(?is)^\\s*explain\\s+");
    private static final Pattern EXPLAIN_OPTIONS = Pattern.compile("(?is)^\\([^)]*\\)\\s*");
    private static final Pattern ANALYZE_WORD = Pattern.compile("(?is)^analyze\\s+");

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
        if (isExplainAnalyzeWrite(body)) {
            // EXPLAIN ANALYZE 真实执行被包裹语句:写语句会落库,只读通道拒绝
            throw new BusinessException(400, "EXPLAIN ANALYZE on a write statement is not allowed "
                    + "(it actually executes the wrapped statement); use plain EXPLAIN without ANALYZE");
        }
    }

    /**
     * EXPLAIN [ (options) ] ANALYZE &lt;写动词&gt; 形态检测(写语句经 ANALYZE 真执行)。
     * 只读包裹(SELECT/SHOW/EXPLAIN 再嵌套)不受限。
     */
    private static boolean isExplainAnalyzeWrite(String body) {
        java.util.regex.Matcher m = EXPLAIN_PREFIX.matcher(body);
        if (!m.find()) {
            return false;
        }
        String rest = body.substring(m.end()).stripLeading();
        java.util.regex.Matcher opts = EXPLAIN_OPTIONS.matcher(rest);
        boolean hasAnalyze = false;
        if (opts.find()) {
            hasAnalyze = opts.group().toLowerCase(Locale.ROOT).contains("analyze");
            rest = rest.substring(opts.end()).stripLeading();
        }
        java.util.regex.Matcher an = ANALYZE_WORD.matcher(rest);
        if (!hasAnalyze) {
            if (!an.find()) {
                return false;
            }
            rest = rest.substring(an.end()).stripLeading();
        }
        String inner = firstWord(rest);
        return MUTATING_PREFIXES.contains(inner);
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
