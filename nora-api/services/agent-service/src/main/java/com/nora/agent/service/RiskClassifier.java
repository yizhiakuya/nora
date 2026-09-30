package com.nora.agent.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 高风险操作分类器:决定一个工具调用在 ASSIST 档下是否需要用户批准。
 * 判定在 harness 层(永不信任模型自评);模型文本中的"同意"不视为批准。
 *
 * <p>2026-09-20 参数理解统一(架构设计 §5.1):解析改用 ObjectMapper
 * (与执行层同源),并共享执行层的字段别名/动作方言归一化——此前分类器用
 * 字符串 indexOf 简易解析且不认 filename/file 别名,「审批检查的目标」
 * 可能与「真正执行的目标」不一致(如 {"action":"write","filename":"D:/区外/x"}
 * 被判为区内 LOW,执行层却按区外写)。
 */
final class RiskClassifier {

    /** 写 SQL 的首个动词白名单之外的一切写动词都算高风险。 */
    private static final Set<String> WRITE_VERBS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate",
            "grant", "revoke", "copy", "vacuum", "reindex", "call", "do", "merge");

    /** SELECT ... INTO / SELECT FOR UPDATE 等伪装只读的写形态。 */
    private static final Pattern HIDDEN_WRITE = Pattern.compile(
            "\\b(into\\s+|for\\s+update\\b)", Pattern.CASE_INSENSITIVE);

    /** 共享解析器(ObjectMapper 线程安全;与执行层同源语义,不再用字符串 indexOf)。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        if ("open_file".equals(toolName)) return Risk.LOW;
        if ("manage_workspace".equals(toolName)) {
            // 工作区语义(对齐 OpenClaw):默认 cwd 而非硬沙箱。
            // 区内:读 LOW、写/追加 LOW(记忆维护需自动)——这与「记住…」必须即时落盘矛盾最小;
            // 区外:读仍 LOW(只读无破坏),写/追加 HIGH,删除 CRITICAL(不可逆)
            return classifyWorkspace(argsJson);
        }
        if ("manage_file".equals(toolName) || "read_file".equals(toolName)) {
            // 文件中心(用户上传文件;2026-09-20 更名 manage_file,read_file 为兼容别名):
            // list/read/folders 只读 LOW;import 写文件(可删可重来)HIGH;
            // rename/move/mkdir 改组织(可逆)HIGH;delete=软删进回收站(可恢复)HIGH。
            // 与 manage_workspace 的区内写同级——文件中心没有"区外"概念。
            String action = actionOf(argsJson);
            if (action == null || action.isBlank()
                    || "list".equalsIgnoreCase(action) || "read".equalsIgnoreCase(action)
                    || "folders".equalsIgnoreCase(action)) {
                return Risk.LOW;
            }
            return Risk.HIGH;
        }
        if ("execute_write_sql".equals(toolName)) {
            return Risk.HIGH;
        }
        if ("manage_container".equals(toolName)) {
            return Risk.HIGH;
        }
        if ("manage_datasource".equals(toolName)) {
            // list/test/schema 只读自动(HIGH 之外的连接探测无副作用);
            // create 改数据源清单,HIGH;remove 删连接+级联历史,CRITICAL。
            // 别名归一化(执行层共用):add→create / delete→remove 等
            String action = normalizeDatasourceAction(actionOf(argsJson));
            if ("remove".equals(action)) {
                return Risk.CRITICAL;
            }
            if ("create".equals(action)) {
                return Risk.HIGH;
            }
            return "list".equals(action) || "test".equals(action)
                    || "schema".equals(action) ? Risk.LOW : Risk.HIGH;
        }
        if ("manage_service".equals(toolName)) {
            // register/remove CRITICAL:PROC 注册=宿主机命令纳入守护,删除不可逆;
            // list 只读 LOW;enable/disable 可逆,HIGH。别名归一化(执行层共用)
            String action = normalizeServiceAction(actionOf(argsJson));
            if ("register".equals(action) || "remove".equals(action)) {
                return Risk.CRITICAL;
            }
            if ("list".equals(action)) {
                return Risk.LOW;
            }
            return Risk.HIGH;
        }
        if ("manage_mcp".equals(toolName)) {
            // MCP 服务器管理:list/tools 只读 LOW(视图已脱敏;tools 读缓存快照不触发远端);
            // call 是「按名调用 lazy 服务器工具」——与 mcp__* 挂载工具同语义,一律 HIGH;
            // 其余动作(register/remove/refresh/enable/disable)改变 Agent 的工具挂载面,
            // 统一 HIGH——跟随全局权限档位(ASK 全问 / ASSIST 询问 / FULL 自动),
            // 不做单独的强制审批;别名归一化与执行层共用,保证判定一致
            String action = normalizeMcpAction(actionOf(argsJson));
            if ("list".equals(action) || "tools".equals(action)) {
                return Risk.LOW;
            }
            return Risk.HIGH;
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
            // list/bases/stats 只读 LOW;index 写知识库(可逆:可 remove 重来)HIGH;
            // remove 删文档+分块不可逆 CRITICAL;reindex 重建向量(可重跑)HIGH;
            // disable/enable 切换检索可见性(可逆,影响 AI 能检索到什么)HIGH
            String action = actionOf(argsJson);
            if ("list".equalsIgnoreCase(action) || "stats".equalsIgnoreCase(action)
                    || "bases".equalsIgnoreCase(action)) {
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
            String action = actionOf(argsJson);
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
            JsonNode a = parseObject(argsJson);
            String folder = a == null ? null : a.path("folder").asText(null);
            return isOutsideWorkspace(folder) ? Risk.HIGH : Risk.LOW;
        }
        if (toolName != null && toolName.startsWith("mcp__")) {
            // 手机相册整理(2026-09-20 photos_manage):写操作分级——
            // trash_purge(彻底删除,不可恢复)与 album 结构操作外的破坏性动作
            // 按 CRITICAL 处理(任何档位都需批准,无人值守通道直接拒绝);
            // 其余写(move/copy/rename/delete→回收站/restore)可恢复,HIGH。
            // delete 只是移入 App 回收站(可恢复),不是 CRITICAL。
            if (toolName.endsWith("__photos_manage")) {
                JsonNode a = parseObject(argsJson);
                String action = a == null ? "" : a.path("action").asText("").trim().toLowerCase();
                // trash_purge = 彻底删除(不可恢复):任何档位都需批准,无人值守直接拒绝
                if ("trash_purge".equals(action)) {
                    return Risk.CRITICAL;
                }
                // trash_list = 只读查看回收站:自动执行
                if ("trash_list".equals(action)) {
                    return Risk.LOW;
                }
                // move/copy/rename/delete(→回收站)/restore/album_create:
                // 写操作但可恢复(delete 只是移入回收站),HIGH 跟随全局档位
                return Risk.HIGH;
            }
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
     *
     * <p>2026-09-20 参数理解统一:字段别名(filename/file)与动作方言
     * (download/save/fetch→import)与执行层 {@code execManageWorkspace}
     * 共用同一归一化——审批检查的目标必须与真正执行的目标一致。
     */
    private static Risk classifyWorkspace(String argsJson) {
        JsonNode a = parseObject(argsJson);
        if (a == null) {
            return Risk.HIGH;
        }
        try {
            String action = normalizeWorkspaceAction(a.path("action").asText(""));
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
            // 写/追加/删除:判定目标是否区外(路径字段别名与执行层一致)
            String target = workspacePathOf(a);
            boolean outside = isOutsideWorkspace(target);
            if ("delete".equals(action)) {
                return outside ? Risk.CRITICAL : Risk.HIGH;
            }
            // move:区外目标(源或目的地)会改整机文件,跟随审批档位;
            // 区内=LOW(整理工作区自己的文件,与 write 同级)。
            // copy:读操作+区内写,源永远只读;区外目标=HIGH,其余 LOW。
            if ("move".equals(action) || "copy".equals(action)) {
                boolean toOutside = isOutsideWorkspace(a.path("to").asText(null));
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

    /**
     * manage_workspace 动作方言归一化(与执行层 execManageWorkspace 共用):
     * download/save/fetch 语义即 import(实测模型写 download)。
     * 输出小写。
     */
    static String normalizeWorkspaceAction(String action) {
        if (action == null) {
            return "";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "download", "save", "fetch" -> "import";
            default -> a;
        };
    }

    /**
     * manage_workspace 的寻址字段解析(与执行层共用别名序):
     * path 优先,其次 filename/file——执行层真正落盘的路径与权限判定
     * 必须来自同一归一化(设计 §5.1:审批检查的目标 = 执行的目标)。
     * list 的 dir 参数只用于列目录(恒 LOW),不参与写类判定。
     */
    static String workspacePathOf(JsonNode a) {
        String path = a.path("path").asText(null);
        if (path == null || path.isBlank()) {
            path = a.path("filename").asText(null);
        }
        if (path == null || path.isBlank()) {
            path = a.path("file").asText(null);
        }
        return path;
    }

    /** JSON 对象解析(非对象/非法/空白 → null,调用方按保守处理)。 */
    private static JsonNode parseObject(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 简单判定:绝对路径或含 ../ 上跳 = 工作区外。 */
    static boolean isOutsideWorkspace(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String cleaned = path.trim().replace(java.io.File.separatorChar, '/');
        return cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*") || cleaned.contains("../");
    }

    /**
     * 提取 args JSON 里的 action 字段(与执行层同源的 JSON 解析;
     * 非对象/非法/缺失 → 空串=未知,调用方按 HIGH 保守处理)。
     */
    static String actionOf(String argsJson) {
        JsonNode a = parseObject(argsJson);
        return a == null ? "" : a.path("action").asText("");
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
            return "拒绝执行「" + action + "」：action 只允许 start / stop / restart"
                    + "(查看状态/详情请用 environment_status 或 read_service_logs)";
        }
        return null;
    }

    /** manage_datasource 动作白名单。 */
    static String validateDatasourceAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:list / create / test / remove";
        }
        String normalized = normalizeDatasourceAction(action);
        if (!Set.of("list", "create", "test", "remove", "schema").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 list / create / test / remove(查看表结构用 schema)";
        }
        return null;
    }

    /**
     * manage_datasource action 别名归一化(2026-09-18 工具复盘数据驱动):
     * 实测模型写 add/delete(受通用 CRUD 词汇影响)被拒——与 manage_mcp 同款
     * 归一化,分类器/执行层/审批明细共用。输出小写。
     */
    static String normalizeDatasourceAction(String action) {
        if (action == null) {
            return "";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "add", "new" -> "create";
            case "delete", "drop", "rm" -> "remove";
            case "check", "ping" -> "test";
            case "tables", "describe", "structure" -> "schema";
            default -> a;
        };
    }

    /** manage_service 动作白名单。 */
    static String validateServiceAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:list / register / enable / disable / remove";
        }
        String normalized = normalizeServiceAction(action);
        if (!Set.of("list", "register", "enable", "disable", "remove").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 list / register / enable / disable / remove";
        }
        return null;
    }

    /** manage_service action 别名归一化(同上,输出小写)。 */
    static String normalizeServiceAction(String action) {
        if (action == null) {
            return "";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "add", "new" -> "register";
            case "delete", "unregister" -> "remove";
            case "pause", "stop" -> "disable";
            case "resume", "start" -> "enable";
            default -> a;
        };
    }

    /** manage_mcp 动作白名单。 */
    static String validateMcpAction(String action) {
        if (action == null || action.isBlank()) {
            return "拒绝执行：缺少 action 参数。可用值:list / refresh / enable / disable / register / update / remove / tools / call / setPolicy";
        }
        String normalized = normalizeMcpAction(action);
        if (!Set.of("list", "refresh", "enable", "disable", "register", "update", "remove", "tools", "call", "setpolicy").contains(normalized)) {
            return "拒绝执行「" + action + "」：action 只允许 "
                    + "list / refresh / enable / disable / register / update(改地址/密钥) / remove / tools(查工具清单) / call(按名调用工具) / setPolicy(设置加载策略)";
        }
        return null;
    }

    /**
     * manage_mcp action 别名归一化:模型受 manage_datasource 的 CRUD 词汇影响常写
     * create/delete/add/unregister——统一映射到 register/remove。
     * 分类器与执行分发必须共用此函数,保持判定一致
     * (create 不能一处按未知处理、另一处按 register 真执行)。
     * 输出一律小写(调用方按小写比较)。
     */
    static String normalizeMcpAction(String action) {
        if (action == null) {
            return "";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "create", "add", "install" -> "register";
            case "delete", "unregister", "uninstall" -> "remove";
            case "invoke" -> "call";
            case "get_tools", "describe", "schema" -> "tools";
            case "set_policy", "policy" -> "setpolicy";
            case "edit", "modify", "set", "update_credentials", "change" -> "update";
            default -> a;
        };
    }

    /**
     * manage_mcp register 参数校验:名称规则与设置页注册一致(挂载名约束);
     * transport=STDIO 时需 command(本地进程),否则需 url(远程端点)。
     */
    /** manage_mcp register 的 transport 别名归一化(2026-09-18:实测模型写 http)。 */
    static String normalizeMcpTransport(String transport) {
        if (transport == null || transport.isBlank()) {
            return "STREAMABLE";
        }
        String t = transport.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "HTTP", "HTTP-STREAM", "STREAMABLE-HTTP", "HTTP_STREAMABLE" -> "STREAMABLE";
            case "SSE-HTTP", "EVENT-STREAM" -> "SSE";
            case "LOCAL", "PROCESS", "COMMAND", "NPX" -> "STDIO";
            default -> t;
        };
    }

    static String validateMcpRegister(String name, String url, String transport, String command, List<String> args) {
        if (name == null || name.isBlank()) {
            return "拒绝执行：缺少 name 参数(MCP 服务器名称)";
        }
        if (!name.trim().matches("^[a-zA-Z0-9_-]+$") || name.trim().contains("__")) {
            return "拒绝执行：服务器名只能包含字母、数字、下划线、连字符,且不能含连续下划线"
                    + "(挂载工具名 mcp__<server>__<tool> 的约束)";
        }
        String t = normalizeMcpTransport(transport);
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
        if (url == null || (!url.trim().startsWith("http") && !url.trim().startsWith("${"))) {
            return "拒绝执行：缺少合法 url(http(s):// 地址,或 ${VAR} 环境变量占位)";
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
