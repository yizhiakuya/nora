package com.nora.agent.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 高风险操作分类器:决定一个工具调用在 ASSIST 档下是否需要用户批准。
 * 判定在 harness 层(永不信任模型自评);模型文本中的"同意"不视为批准。
 */
final class RiskClassifier {

    /** 写 SQL 的首个动词白名单之外的一切写动词都算高风险。 */
    private static final Set<String> WRITE_VERBS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "vacuum", "reindex", "call", "do", "merge");

    /** SELECT ... INTO / SELECT FOR UPDATE 等伪装只读的写形态。 */
    private static final Pattern HIDDEN_WRITE = Pattern.compile(
            "\\b(into\\s+|for\\s+update\\b)", Pattern.CASE_INSENSITIVE);

    private RiskClassifier() {
    }

    enum Risk {
        /** 只读:ASSIST/FULL 档自动执行 */
        LOW,
        /** 写操作:ASSIST 档需批准,FULL 档自动执行 */
        HIGH
    }

    /**
     * @param toolName 工具名
     * @param argsJson 模型填的参数 JSON
     */
    static Risk classify(String toolName, String argsJson) {
        if ("execute_write_sql".equals(toolName)) {
            return Risk.HIGH;
        }
        if ("manage_container".equals(toolName)) {
            return Risk.HIGH;
        }
        return Risk.LOW;
    }

    /** 写 SQL 的动词校验(拒绝多语句与未知动词,与 SqlGuard 语义一致)。 */
    static String validateWriteSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "拒绝执行：缺少 sql 参数。该工具执行写操作(INSERT/UPDATE/DELETE/DDL),"
                    + "正确示例:{\"sql\": \"UPDATE orders SET status='paid' WHERE id=42\"}";
        }
        String trimmed = sql.trim();
        String withoutTrailing = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (withoutTrailing.contains(";")) {
            return "拒绝执行：一次只允许一条写语句(检测到多条)。请拆成多次调用";
        }
        String firstWord = firstWord(withoutTrailing);
        if (!WRITE_VERBS.contains(firstWord)) {
            return "拒绝执行「" + firstWord + "」：execute_write_sql 只接受写动词(INSERT/UPDATE/DELETE/DDL)。"
                    + "只读查询请用 execute_sql 工具";
        }
        if (firstWord.equals("select") || HIDDEN_WRITE.matcher(withoutTrailing).find() && firstWord.equals("select")) {
            return "拒绝执行：SELECT 不是写语句,只读查询请用 execute_sql 工具";
        }
        if (withoutTrailing.length() > 10000) {
            return "拒绝执行：SQL 超过 10000 字符上限";
        }
        return null;
    }

    /** 容器操作的动作白名单。 */
    static String validateContainerAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:start / stop / restart";
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("start", "stop", "restart").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 start / stop / restart";
        }
        return null;
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
