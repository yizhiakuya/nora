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
        HIGH,
        /** 不可逆/带外操作(删连接、注册进程命令):任何权限档都需批准 */
        CRITICAL
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
        if ("manage_datasource".equals(toolName)) {
            // list/test/schema 只读自动(HIGH 之外的连接探测无副作用);
            // create 改数据源清单,HIGH;remove 删连接+级联历史,CRITICAL
            String action = extractAction(argsJson);
            if ("remove".equalsIgnoreCase(action)) {
                return Risk.CRITICAL;
            }
            if ("create".equalsIgnoreCase(action)) {
                return Risk.HIGH;
            }
            return "list".equalsIgnoreCase(action) || "test".equalsIgnoreCase(action)
                    || "schema".equalsIgnoreCase(action) ? Risk.LOW : Risk.HIGH;
        }
        if ("manage_service".equals(toolName)) {
            // register/remove CRITICAL:PROC 注册=宿主机命令纳入守护,删除不可逆;
            // list 只读 LOW;enable/disable 可逆,HIGH
            String action = extractAction(argsJson);
            if ("register".equalsIgnoreCase(action) || "remove".equalsIgnoreCase(action)) {
                return Risk.CRITICAL;
            }
            if ("list".equalsIgnoreCase(action)) {
                return Risk.LOW;
            }
            return Risk.HIGH;
        }
        return Risk.LOW;
    }

    /** 提取 args JSON 里的 action 字段(解析失败按空串=未知,按 HIGH 处理)。 */
    private static String extractAction(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        try {
            int idx = argsJson.indexOf("\"action\"");
            if (idx < 0) {
                return "";
            }
            int colon = argsJson.indexOf(':', idx);
            int quoteStart = argsJson.indexOf('"', colon + 1);
            int quoteEnd = argsJson.indexOf('"', quoteStart + 1);
            return quoteStart < 0 || quoteEnd < 0 ? "" : argsJson.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return "";
        }
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

    /** manage_datasource 动作白名单。 */
    static String validateDatasourceAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:list / create / test / remove";
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("list", "create", "test", "remove").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 list / create / test / remove";
        }
        return null;
    }

    /** manage_service 动作白名单。 */
    static String validateServiceAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:list / register / enable / disable / remove";
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("list", "register", "enable", "disable", "remove").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 list / register / enable / disable / remove";
        }
        return null;
    }

    /** 数据源 create 参数校验:engine 只支持白名单(JdbcConnections 同款)。 */
    static String validateDatasourceCreate(String engine, String host, Integer port, String database) {
        if (engine == null || !Set.of("postgresql", "mysql").contains(engine.trim().toLowerCase(Locale.ROOT))) {
            return "拒绝执行：engine 只支持 postgresql / mysql(当前:" + engine + ")";
        }
        if (host == null || host.isBlank()) {
            return "拒绝执行：缺少 host 参数,无法构造 JDBC 连接";
        }
        if (port == null || port < 1 || port > 65535) {
            return "拒绝执行：缺少合法 port(1-65535)";
        }
        if (database == null || database.isBlank()) {
            return "拒绝执行：缺少 database 参数";
        }
        return null;
    }

    /** 纳管源 register 参数校验:kind 与对应字段必须匹配(与 env-service 语义一致)。 */
    static String validateServiceRegister(String kind, String fileLogPath, String containerName, String command) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("FILE", "DOCKER", "PROC").contains(k)) {
            return "拒绝执行：kind 只允许 FILE / DOCKER / PROC(当前:" + kind + ")";
        }
        return switch (k) {
            case "FILE" -> (fileLogPath == null || fileLogPath.isBlank())
                    ? "拒绝执行:FILE 源必须提供 fileLogPath" : null;
            case "DOCKER" -> (containerName == null || containerName.isBlank())
                    ? "拒绝执行:DOCKER 源必须提供 containerName" : null;
            default -> (command == null || command.isBlank())
                    ? "拒绝执行:PROC 源必须提供 command(启动命令)" : null;
        };
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
