package com.nora.agent.service;

import java.util.List;
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
        if ("manage_workspace".equals(toolName)) {
            // 工作区语义(对齐 OpenClaw):默认 cwd 而非硬沙箱。
            // 区内:读 LOW、写/追加 LOW(记忆维护需自动)——这与「记住…」必须即时落盘矛盾最小;
            // 区外:读仍 LOW(只读无破坏),写/追加 HIGH,删除 CRITICAL(不可逆)
            return classifyWorkspace(argsJson);
        }
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
        if ("manage_mcp".equals(toolName)) {
            // MCP 服务器管理:list 只读 LOW(视图已脱敏);其余动作
            // (register/remove/refresh/enable/disable)改变 Agent 的工具挂载面,
            // 统一 HIGH——跟随全局权限档位(ASK 全问 / ASSIST 询问 / FULL 自动),
            // 不做单独的强制审批;别名归一化与执行层共用,保证判定一致
            String action = normalizeMcpAction(extractAction(argsJson));
            return "list".equals(action) ? Risk.LOW : Risk.HIGH;
        }
        if ("run_command".equals(toolName)) {
            // 本机终端:一律 HIGH(跟随全局档位——ASK 全问 / ASSIST 询问 / FULL 自动)。
            // 不做命令解析白名单:那是假安全(管道/子 shell/编码绕过随手可得),
            // 真正的防线是审批卡完整展示命令 + 用户档位选择。
            return Risk.HIGH;
        }
        if ("search_knowledge".equals(toolName)) {
            // 知识库主动检索:只读,无副作用
            return Risk.LOW;
        }
        if ("manage_knowledge".equals(toolName)) {
            // list/stats 只读 LOW;index 写知识库(可逆:可 remove 重来)HIGH;
            // remove 删文档+分块不可逆 CRITICAL;reindex 重建向量(可重跑)HIGH
            String action = extractAction(argsJson);
            if ("list".equalsIgnoreCase(action) || "stats".equalsIgnoreCase(action)) {
                return Risk.LOW;
            }
            if ("remove".equalsIgnoreCase(action)) {
                return Risk.CRITICAL;
            }
            return Risk.HIGH;
        }
        if ("manage_automation".equals(toolName)) {
            // list/executions 只读 LOW;create/toggle/run 改变未来自动执行面 HIGH;
            // remove 删除规则(历史保留)可重建,但属破坏性操作——CRITICAL
            String action = extractAction(argsJson);
            if ("list".equalsIgnoreCase(action) || "executions".equalsIgnoreCase(action)) {
                return Risk.LOW;
            }
            if ("remove".equalsIgnoreCase(action)) {
                return Risk.CRITICAL;
            }
            return Risk.HIGH;
        }
        if ("environment_status".equals(toolName)) {
            // 环境健康快照:只读,无副作用
            return Risk.LOW;
        }
        if ("fetch_media".equals(toolName)) {
            // 批量媒体拉取:有出站请求 + 落盘,但目标在**工作区内**
            // (与 manage_workspace import 同语义:区内落盘 LOW,不打断
            // 「把相册整理进来」这类明确用户意图;区外路径=HIGH 跟随档位)。
            // folder 字段走与 workspace 相同的区外判定。
            String folder = extractStringField(argsJson, "folder");
            return isOutsideWorkspace(folder) ? Risk.HIGH : Risk.LOW;
        }
        if (toolName != null && toolName.startsWith("mcp__")) {
            // MCP 挂载工具:外部服务器能力未知,一律 HIGH——ASSIST 档询问、
            // FULL 档放行、无人值守通道按设计放行(见 CLAUDE.md 高风险工具节)
            return Risk.HIGH;
        }
        return Risk.LOW;
    }

    /**
     * manage_workspace 的风险分级:解析 args 的 action 与 path,
     * 判断目标是否在工作区内(相对路径=区内;绝对路径/.. = 区外)。
     * 解析失败按 HIGH 保守处理。
     */
    private static Risk classifyWorkspace(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Risk.HIGH;
        }
        try {
            String action = extractAction(argsJson).toLowerCase(Locale.ROOT);
            if ("list".equals(action) || "read".equals(action)) {
                return Risk.LOW; // 读操作无副作用(含区外读)
            }
            boolean knownAction = "write".equals(action) || "append".equals(action)
                    || "delete".equals(action) || "import".equals(action)
                    || "move".equals(action) || "copy".equals(action) || "mkdir".equals(action)
                    || "edit".equals(action);
            if (!knownAction) {
                return Risk.HIGH; // 参数坏/action 未知:保守按 HIGH
            }
            // 写/追加/删除:判定目标是否区外
            String path = extractStringField(argsJson, "path");
            String dir = extractStringField(argsJson, "dir");
            String target = path != null ? path : dir;
            boolean outside = isOutsideWorkspace(target);
            if ("delete".equals(action)) {
                return outside ? Risk.CRITICAL : Risk.HIGH;
            }
            // move:区外目标(源或目的地)会改整机文件,跟随审批档位;
            // 区内=LOW(整理工作区自己的文件,与 write 同级)。
            // copy:读操作+区内写,源永远只读;区外目标=HIGH,其余 LOW。
            if ("move".equals(action) || "copy".equals(action)) {
                String to = extractStringField(argsJson, "to");
                boolean toOutside = isOutsideWorkspace(to);
                return outside || toOutside ? Risk.HIGH : Risk.LOW;
            }
            // import:从远程 URL 下载并落盘(有出站请求 + 写文件)。
            // 区内落盘=LOW(与区内 write 同级:用户请求把相册存进工作区时不应被打断);
            // 区外落盘=HIGH(整机任意位置,跟随审批档位)
            if ("import".equals(action)) {
                return outside ? Risk.HIGH : Risk.LOW;
            }
            // write / append:区内=LOW(记忆维护须即时落盘,不打扰);区外=HIGH
            return outside ? Risk.HIGH : Risk.LOW;
        } catch (Exception e) {
            return Risk.HIGH;
        }
    }

    /** 简单判定:绝对路径或含 ../ 上跳 = 工作区外。 */
    private static boolean isOutsideWorkspace(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String cleaned = path.trim().replace(java.io.File.separatorChar, '/');
        return cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*") || cleaned.contains("../");
    }

    /** 提取 args JSON 里的指定字符串字段(简易解析,失败返回 null)。 */
    private static String extractStringField(String argsJson, String field) {
        try {
            int idx = argsJson.indexOf("\"" + field + "\"");
            if (idx < 0) {
                return null;
            }
            int colon = argsJson.indexOf(':', idx);
            int quoteStart = argsJson.indexOf('"', colon + 1);
            int quoteEnd = argsJson.indexOf('"', quoteStart + 1);
            return quoteStart < 0 || quoteEnd < 0 ? null : argsJson.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return null;
        }
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

    /** manage_mcp 动作白名单。 */
    static String validateMcpAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:list / refresh / enable / disable / register / remove";
        }
        String normalized = normalizeMcpAction(action);
        if (!Set.of("list", "refresh", "enable", "disable", "register", "remove").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 list / refresh / enable / disable / register / remove";
        }
        return null;
    }

    /**
     * manage_mcp action 别名归一化:模型受 manage_datasource 的 CRUD 词汇影响常写
     * create/delete/add/unregister——统一映射到 register/remove。
     * 分类器与执行分发必须共用此函数,保持判定一致
     * (create 不能一处按未知处理、另一处按 register 真执行)。
     */
    static String normalizeMcpAction(String action) {
        if (action == null) {
            return "";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "create", "add", "install" -> "register";
            case "delete", "unregister", "uninstall" -> "remove";
            default -> a;
        };
    }

    /**
     * manage_mcp register 参数校验:名称规则与设置页注册一致(挂载名约束);
     * transport=STDIO 时需 command(本地进程),否则需 url(远程端点)。
     */
    static String validateMcpRegister(String name, String url, String transport, String command, List<String> args) {
        if (name == null || name.isBlank()) {
            return "拒绝执行：缺少 name 参数(MCP 服务器名称)";
        }
        if (!name.trim().matches("^[a-zA-Z0-9_-]+$") || name.trim().contains("__")) {
            return "拒绝执行：服务器名只能包含字母、数字、下划线、连字符,且不能含连续下划线"
                    + "(挂载工具名 mcp__<server>__<tool> 的约束)";
        }
        String t = transport == null || transport.isBlank() ? "STREAMABLE" : transport.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("STREAMABLE", "SSE", "STDIO").contains(t)) {
            return "拒绝执行：transport 只支持 STREAMABLE / SSE / STDIO(当前:" + transport + ")";
        }
        if ("STDIO".equals(t)) {
            // 本地进程:command 必填;args 里不得混入 shell 元字符拼接(整条命令交给
            // ProcessBuilder 数组执行,不经过 shell,元字符只是普通字符——但拒绝空项)
            if (command == null || command.isBlank()) {
                return "拒绝执行：STDIO 必须提供 command(可执行命令,如 npx / node / docker)";
            }
            if (args != null) {
                for (String arg : args) {
                    if (arg == null || arg.isBlank()) {
                        return "拒绝执行：args 含空项,请给出每个参数的完整值";
                    }
                }
            }
            return null;
        }
        if (url == null || !url.trim().startsWith("http")) {
            return "拒绝执行：缺少合法 url(http(s):// 地址)";
        }
        return null;
    }

    /** 兼容重载(仅远程传输)。 */
    static String validateMcpRegister(String name, String url, String transport) {
        return validateMcpRegister(name, url, transport, null, null);
    }

    /** 数据源 create 参数校验:engine 只支持白名单(JdbcConnections 同款)。 */
    static String validateDatasourceCreate(String engine, String host, Integer port, String database) {
        if (engine == null || !Set.of("postgresql", "mysql", "redis").contains(engine.trim().toLowerCase(Locale.ROOT))) {
            return "拒绝执行：engine 只支持 postgresql / mysql / redis(当前:" + engine + ")";
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
