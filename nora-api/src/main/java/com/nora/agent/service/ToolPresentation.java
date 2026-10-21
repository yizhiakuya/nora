package com.nora.agent.service;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatStepDto;

/**
 * 工具调用的展示层：参数解析、标题派生、审批载荷与日志脱敏。
 *
 * <p>只回答「这次调用看起来是什么」——不执行、不判定权限、不写库；
 * 执行与终态由 {@link ToolStepEmitter} 负责。步骤、审批卡与日志的文本都出自这里，
 * 因此凭据必须在离开本类前脱敏。
 */
class ToolPresentation {

    /** 单条工具参数重放上限：超过则不附 rawArgs（该步退化为文本历史，防病态超长参数）。 */
    private static final int MAX_REPLAY_ARGS_CHARS = 20_000;

    private final ObjectMapper objectMapper;
    private final ChatToolExecutor toolExecutor;

    ToolPresentation(ObjectMapper objectMapper, ChatToolExecutor toolExecutor) {
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
    }


    /** 参数仅用于展示；执行时由 SDK 绑定方法签名。 */
    record ParsedArgs(ChatStepDto.StepInput input, String description, String action, JsonNode arguments) { }

    /** 把工具参数解析为前端展示的带类型入参 + 展示标题。 */
    ParsedArgs parseArgs(String toolName, String argsJson) {
        JsonNode node = parseArgsSafe(argsJson);
        try {
            String description = node.path("description").asText(null);
            if (description != null && description.length() > 120) {
                description = description.substring(0, 120);
            }
            String action = node.path("action").asText(null);
            switch (toolName) {
                case "manage_env_vars" -> {
                    String key = node.path("key").asText("").trim().toUpperCase(java.util.Locale.ROOT);
                    String target = key.matches("[A-Z][A-Z0-9_]*") ? key : "环境变量";
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target), null, action, node);
                }
                case "execute_sql" -> {
                    return new ParsedArgs(new ChatStepDto.StepInput(node.path("sql").asText(null), null, null,
                                    node.path("datasource").asText(null)),
                            description, null, node);
                }
                case "manage_datasource", "manage_service" -> {
                    String target = node.has("name") ? node.path("name").asText(null)
                            : node.has("id") ? node.path("id").asText(null)
                            : node.path("target").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, action, node);
                }
                case "manage_file", "read_file" -> {
                    // 寻址参数:数字 id 为主;模型常写 path(路径路由,2026-09-18
                    // 文件工具分析)或 filename 别名——都提取到 target 交给执行层路由
                    String target = node.path("id").asText(null);
                    if (target == null || target.isBlank()) {
                        target = node.path("path").asText(null);
                    }
                    if (target == null || target.isBlank()) {
                        target = node.path("filename").asText(null);
                    }
                    Integer limit = node.has("limit") && node.get("limit").isNumber()
                            ? node.get("limit").asInt() : null;
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, limit, target),
                            description, action, node);
                }
                case "manage_workspace" -> {
                    // 展示路径与权限判定/执行层同一别名序(2026-09-20);根目录 list 无 path,用 dir 兜底
                    String p1 = RiskClassifier.workspacePathOf(node);
                    if (p1 == null || p1.isBlank()) {
                        p1 = node.path("dir").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, p1),
                            description, action, node);
                }
                case "manage_photo_album" -> {
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            node.path("album").asText("相册目录")), description, action, node);
                }
                case "manage_browser" -> {
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            node.path("url").asText(node.path("selector").asText("当前网页"))), description, action, node);
                }
                case "fetch_media" -> {
                    // 展示目标文件夹(无则 imports);审批卡与折叠行都显示它
                    String folder = node.path("folder").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            folder == null || folder.isBlank() ? "imports" : folder),
                            description, action, node);
                }
                case "manage_skill", "manage_mcp" -> {
                    // read/update/remove 用 target(名称或 id);create/register 用 name;
                    // call 展示「服务器.工具名」;tools 展示服务器名(tool 参数时附工具名)
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("name").asText(null);
                    }
                    String toolArg = node.path("tool").asText("");
                    if (("call".equalsIgnoreCase(action) || "tools".equalsIgnoreCase(action))
                            && target != null && !toolArg.isBlank()) {
                        target = target + "." + toolArg;
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, action, node);
                }
                case "search_knowledge" -> {
                    // 查询词放 target(折叠行展示「检索: xxx」)
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            node.path("query").asText(null)), description, action, node);
                }
                case "manage_knowledge" -> {
                    // list/remove/reindex 用 target(id);index 用 fileId;list 可用 filter
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("fileId").asText(null);
                    }
                    if (target == null) {
                        target = node.path("filter").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, action, node);
                }
                case "manage_automation" -> {
                    // toggle/remove/run 用 target(id);create 用 name
                    String target = node.path("target").asText(null);
                    if (target == null) {
                        target = node.path("name").asText(null);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, target),
                            description, action, node);
                }
                case "powershell", "sh" -> {
                    // 命令原文放 target 字段(折叠行与审批卡都要完整可见);
                    // cwd 不进 typed input(避免误当"目标"显示)
                    String cmd = node.path("command").asText(null);
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null, cmd),
                            description, action, node);
                }
                case "ask_user" -> {
                    // 问题原文放 target(折叠行展示「向用户提问: …」)
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null,
                            Texts.abbreviate(node.path("question").asText(null), 120)),
                            description, action, node);
                }
                default -> {
                    if (node.has("action") && node.has("service")) {
                        return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), null),
                                description, node.path("action").asText(null), node);
                    }
                    if (node.has("service") || node.has("limit")) {
                        Integer limit = node.has("limit") && node.get("limit").isNumber()
                                ? node.get("limit").asInt() : null;
                        return new ParsedArgs(new ChatStepDto.StepInput(null, node.path("service").asText(null), limit),
                                description, null, node);
                    }
                    return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), description, null, node);
                }
            }
        } catch (Exception e) {
            return new ParsedArgs(new ChatStepDto.StepInput(null, null, null), null, null, node);
        }
    }

    /**
     * 派生标题(2026-09-29):模型没填 description 时,从解析后的参数派生
     * 「动作 + 目标」——比静态工具名有信息量(「读取 memory/x.md」而不是
     * 「工作区文件」),且新工具零改动(统一走 ParsedArgs)。
     * 派生不出时回退 {@link #defaultTitle}。
     */
    String derivedTitle(String name, ParsedArgs parsed) {
        String action = parsed.action(); // action/子命令(manage_* 工具)
        String target = parsed.input() != null ? parsed.input().target() : null;
        String sql = parsed.input() != null ? parsed.input().sql() : null;
        String service = parsed.input() != null ? parsed.input().service() : null;
        return switch (name == null ? "" : name) {
            case "execute_sql" -> {
                // 从 SQL 提取表名派生(「查询 chat_message」比「查询数据库」有信息量)
                String table = firstTableOf(sql);
                if (table != null) {
                    yield "执行 SQL · " + table;
                }
                yield defaultTitle(name);
            }
            case "read_service_logs" -> service != null && !service.isBlank()
                    ? "读取 " + service + " 日志" : defaultTitle(name);
            case "manage_workspace", "manage_file", "read_file" -> {
                String act = actionLabel(action);
                if (act != null && target != null && !target.isBlank()) {
                    yield act + " " + Texts.abbreviate(target, 40);
                }
                if (target != null && !target.isBlank()) {
                    yield Texts.abbreviate(target, 40);
                }
                yield defaultTitle(name);
            }
            case "manage_datasource", "manage_service", "manage_mcp", "manage_env_vars", "manage_skill",
                 "manage_knowledge", "manage_automation" -> {
                if (target != null && !target.isBlank()) {
                    String act = actionLabel(action);
                    yield act != null ? act + " " + Texts.abbreviate(target, 40)
                            : Texts.abbreviate(target, 40);
                }
                yield defaultTitle(name);
            }
            case "powershell", "sh" -> target != null && !target.isBlank()
                    ? Texts.abbreviate(target, 48) : defaultTitle(name);
            case "search_knowledge" -> target != null && !target.isBlank()
                    ? "检索「" + Texts.abbreviate(target, 30) + "」" : defaultTitle(name);
            case "fetch_media" -> target != null && !target.isBlank()
                    ? "拉取媒体到 " + Texts.abbreviate(target, 30) : defaultTitle(name);
            default -> defaultTitle(name);
        };
    }

    /** 常见 action 的中文标签(派生标题用;未知/缺失返回 null)。 */
    static String actionLabel(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        return switch (action.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "list" -> "列出";
            case "read" -> "读取";
            case "write" -> "写入";
            case "set" -> "设置";
            case "append" -> "追加";
            case "edit", "update" -> "编辑";
            case "delete", "remove" -> "删除";
            case "import" -> "导入";
            case "move" -> "移动";
            case "copy" -> "复制";
            case "mkdir" -> "新建目录";
            case "index" -> "索引进知识库";
            case "reindex" -> "重建索引";
            case "register" -> "注册";
            case "refresh" -> "刷新";
            case "enable" -> "启用";
            case "disable" -> "停用";
            case "create" -> "创建";
            case "toggle" -> "切换";
            case "run" -> "运行";
            case "stats" -> "统计";
            case "call" -> "调用";
            case "executions" -> "执行记录";
            default -> null;
        };
    }

    /**
     * 从 SQL 里提取首个表名(派生标题用):FROM/JOIN/UPDATE/INTO 之后的
     * 标识符,带 schema 前缀时只取表名部分。提取不到返回 null。
     */
    static String firstTableOf(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\b(?:from|join|update|into)\\s+([A-Za-z_][\\w.]*)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!m.find()) {
            return null;
        }
        String ident = m.group(1);
        int dot = ident.lastIndexOf('.');
        return dot >= 0 ? ident.substring(dot + 1) : ident;
    }

    /** 模型省略 description 参数时的人类可读标题。 */
    String defaultTitle(String name) {
        return switch (name) {
            case "execute_sql" -> "执行数据库命令";
            case "read_service_logs" -> "读取服务日志";
            case "manage_container" -> "容器操作";
            case "manage_datasource" -> "数据源管理";
            case "manage_service" -> "环境服务";
            case "load_tools" -> "加载专用工具";
            case "manage_file", "read_file" -> "文件中心";
            case "manage_workspace" -> "工作区文件";
            case "manage_photo_album" -> "照片成长相册";
            case "manage_browser" -> "内置浏览器";
            case "manage_skill" -> "技能管理";
            case "manage_mcp" -> "MCP 服务器管理";
            case "manage_env_vars" -> "环境变量管理";
            case "powershell" -> "运行 PowerShell";
            case "sh" -> "运行 Shell";
            case "fetch_media" -> "拉取媒体文件";
            case "search_knowledge" -> "检索知识库";
            case "manage_knowledge" -> "知识库管理";
            case "manage_automation" -> "自动任务管理";
            case "environment_status" -> "环境状态快照";
            case "ask_user" -> "向用户提问";
            default -> {
                // 手机相册整理(2026-09-20):任意挂载名下的 photos_manage 都给
                // 可读标题,与泛化「调用 MCP 工具」区分,时间线一眼可读
                if (name.startsWith("mcp__") && name.endsWith("__photos_manage")) {
                    yield "整理手机相册";
                }
                yield name.startsWith("mcp__") ? "调用 MCP 工具" : name;
            }
        };
    }

    /** approval_required 事件载荷:操作类型、目标、参数摘要、风险说明、参数明细。 */
    ApprovalRequestDto buildApprovalRequest(String stepId, String toolName, ParsedArgs parsed, String rawArgs) {
        String actionType;
        String target;
        String risk;
        String detail = null;
        switch (toolName) {
            case "manage_env_vars" -> {
                actionType = "env_vars_manage";
                target = parsed.input().target();
                detail = "操作: " + parsed.action() + "\n变量: " + target + "\n变量值已隐藏";
                risk = "将修改工作台环境变量，影响后续命令及引用它的 MCP 连接";
            }
            case "manage_browser" -> {
                actionType = "browser";
                target = parsed.input().target();
                detail = "网页操作: " + parsed.action() + "\n参数: " + rawArgs;
                risk = "点击、输入或键盘操作可能提交网页表单或修改网站数据";
            }
            case "manage_photo_album" -> {
                actionType = "workspace_file";
                target = parsed.input().target();
                detail = "相册: " + target + "\n操作: " + parsed.action() + "\n参数: " + rawArgs;
                risk = "工作区内相册操作，生成新版本，源照片和旧版保持可用";
            }
            case "execute_sql" -> {
                actionType = "sql";
                target = parsed.input().target() != null
                        ? "数据源 " + parsed.input().target()
                        : "数据库(默认连接)";
                detail = "SQL: " + (parsed.input().sql() == null ? "(空)" : parsed.input().sql());
                risk = "数据库命令可能修改真实数据，按当前权限档位执行";
            }
            case "manage_container" -> {
                actionType = "container_control";
                target = parsed.input().service() == null ? "未知容器" : parsed.input().service();
                detail = "操作: " + parsed.action();
                risk = "停止/重启容器会导致该服务短暂不可用";
            }
            case "manage_datasource" -> {
                actionType = "datasource_manage";
                // 与分类器/执行层同一归一化:别名 add→create / delete→remove 等按目标语义展示明细
                String action = RiskClassifier.normalizeDatasourceAction(parsed.action());
                target = parsed.input().target() == null ? "新数据源" : parsed.input().target();
                JsonNode a = parsed.arguments();
                if ("remove".equals(action)) {
                    detail = "数据源: " + target + "\n后果: 连接记录 + 全部查询历史一并删除";
                    risk = "不可恢复的删除操作";
                } else if ("create".equals(action)) {
                    detail = "名称: " + a.path("name").asText("?")
                            + "\n类型: " + a.path("engine").asText("?")
                            + "\n地址: " + a.path("host").asText("?") + ":" + a.path("port").asInt(0)
                            + "\n数据库: " + a.path("database").asText("?")
                            + "\n用户: " + a.path("username").asText("(空)")
                            + "\n密码: (已隐藏,仅存入数据源服务)";
                    risk = "将新增一个数据库连接";
                } else {
                    risk = "对数据源连接执行 " + action + " 操作";
                }
            }
            case "manage_service" -> {
                actionType = "service_manage";
                // 与分类器/执行层同一归一化(add→register / delete→remove 等)
                String action = RiskClassifier.normalizeServiceAction(parsed.action());
                target = parsed.input().target() == null ? "新纳管源" : parsed.input().target();
                JsonNode a = parsed.arguments();
                if ("register".equals(action)) {
                    String kind = a.path("kind").asText("?");
                    detail = "名称: " + a.path("name").asText("?") + "\n类型: " + kind;
                    if ("FILE".equalsIgnoreCase(kind)) {
                        detail += "\n日志文件: " + a.path("fileLogPath").asText("?");
                    } else if ("DOCKER".equalsIgnoreCase(kind)) {
                        detail += "\n容器: " + a.path("containerName").asText("?");
                    } else {
                        detail += "\n启动命令: " + a.path("command").asText("?")
                                + "\n工作目录: " + a.path("workDir").asText("(无)");
                    }
                    risk = "PROC 源的命令将在宿主机被执行并被持续监控";
                } else if ("remove".equals(action)) {
                    detail = "纳管源: " + target + "(容器/文件本身不受影响)";
                    risk = "删除后该源不再被监控与采集日志";
                } else if (Set.of("start", "stop", "restart").contains(action)) {
                    detail = "服务: " + target + "\n操作: " + action;
                    risk = "停止或重启会使该服务暂时不可用";
                } else {
                    risk = "对纳管源执行 " + action + " 操作";
                }
            }
            case "manage_mcp" -> {
                actionType = "mcp_manage";
                // 与分类器/执行层同一归一化:别名 create/delete 等按 register/remove 展示明细
                String action = RiskClassifier.normalizeMcpAction(parsed.action());
                JsonNode a = parsed.arguments();
                if ("register".equals(action)) {
                    // 与执行层同一推断:有 command 无 url → STDIO(模型常省略 transport)
                    // 且与执行层同一方言归一化(http→STREAMABLE 等)
                    String transport = a.path("transport").asText("");
                    if (transport.isBlank() && !a.path("command").asText("").isBlank()
                            && a.path("url").asText("").isBlank()) {
                        transport = "STDIO";
                    }
                    transport = RiskClassifier.normalizeMcpTransport(transport);
                    target = a.path("name").asText("新 MCP 服务器");
                    JsonNode headers = a.path("headers");
                    StringBuilder headerKeys = new StringBuilder();
                    if (headers.isObject()) {
                        headers.fieldNames().forEachRemaining(k ->
                                headerKeys.append(headerKeys.length() > 0 ? ", " : "").append(k));
                    }
                    JsonNode env = a.path("env");
                    StringBuilder envKeys = new StringBuilder();
                    if (env.isObject()) {
                        env.fieldNames().forEachRemaining(k ->
                                envKeys.append(envKeys.length() > 0 ? ", " : "").append(k));
                    }
                    if ("STDIO".equalsIgnoreCase(transport)) {
                        // 本地进程:完整命令行给用户审(装的是什么包一眼可见)
                        StringBuilder cmdline = new StringBuilder(a.path("command").asText("?"));
                        if (a.path("args").isArray()) {
                            for (JsonNode n : a.path("args")) {
                                cmdline.append(' ').append(n.asText(""));
                            }
                        }
                        detail = "本地命令: " + cmdline
                                + (envKeys.length() > 0 ? "\n环境变量: " + envKeys + "(值已隐藏)" : "");
                        risk = "将在本机启动本地进程作为 MCP 服务器(进程有本机权限,其工具会挂载给 Agent 调用)";
                    } else {
                        detail = "名称: " + target
                                + "\n地址: " + a.path("url").asText("?")
                                + "\n传输: " + transport
                                + (headerKeys.length() > 0 ? "\n鉴权头: " + headerKeys + "(值已隐藏)" : "");
                        risk = "将注册外部 MCP 服务器:其工具会挂载给 Agent 调用(外部能力,执行范围未知)";
                    }
                } else if ("remove".equals(action)) {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    detail = "MCP 服务器: " + target + "\n后果: 注册记录与连接一并删除";
                    risk = "删除后其工具立即不可用,需重新注册才能恢复";
                } else if ("update".equals(action)) {
                    target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText("?"));
                    JsonNode upHeaders = a.path("headers");
                    StringBuilder upHeaderKeys = new StringBuilder();
                    if (upHeaders.isObject()) {
                        upHeaders.fieldNames().forEachRemaining(k ->
                                upHeaderKeys.append(upHeaderKeys.length() > 0 ? ", " : "").append(k));
                    }
                    detail = "服务器: " + target
                            + (a.path("url").asText("").isBlank() ? "" : "\n新地址: " + a.path("url").asText(""))
                            + (upHeaders.isObject()
                                ? (upHeaderKeys.length() == 0
                                    ? "\n鉴权头: 清除全部"
                                    : "\n鉴权头: " + upHeaderKeys + "(值已隐藏)")
                                : "");
                    risk = "将更新 MCP 服务器的连接配置(地址/鉴权),旧连接失效后以新配置重连";
                } else if ("call".equals(action)) {
                    // 按名调用外部工具:与 mcp_tool 同语义——服务器+工具名+参数可见
                    target = a.path("target").asText("?");
                    detail = "服务器: " + target + "\n工具: " + a.path("tool").asText("?")
                            + "\n参数: " + Texts.abbreviate(a.path("arguments").toString(), 400);
                    risk = "调用外部 MCP 服务器提供的工具,能力未知,执行前需确认";
                } else if ("setpolicy".equals(action)) {
                    target = a.path("target").asText("?");
                    detail = "服务器: " + target + "\n策略: " + a.path("toolPolicy").asText("?")
                            + (("lazy".equalsIgnoreCase(a.path("toolPolicy").asText("")))
                            ? "\n后果: 其工具不再挂载(改用 tools/call 按需调用,省每轮上下文)"
                            : "\n后果: 其工具重新挂载为独立工具(每轮注入)");
                    risk = "改变 Agent 的工具挂载面";
                } else if ("get".equals(action)) {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    risk = "只读查看 MCP 连接配置，鉴权值与 URL 查询参数隐藏，不发起远程连接";
                } else {
                    target = parsed.input().target() == null ? "?" : parsed.input().target();
                    risk = "对 MCP 服务器执行 " + action + " 操作";
                }
            }
            case "powershell", "sh" -> {
                actionType = "terminal_command";
                JsonNode a = parsed.arguments();
                String cmd = a.path("command").asText("?");
                String cwd = a.path("cwd").asText(null);
                Integer timeout = a.path("timeout").isInt() ? a.path("timeout").asInt() : null;
                // 命令原文完整展示(与 Claude Code 同款防线:用户审的就是将执行的)
                target = Texts.abbreviate(cmd, 60);
                detail = "命令: " + cmd
                        + (cwd != null ? "\n工作目录: " + cwd : "\n工作目录: 工作区根")
                        + "\n执行器: " + toolName
                        + (timeout != null ? "\n超时: " + timeout + "s" : "");
                risk = "命令将在本机以当前用户权限执行,可能有文件/网络副作用";
            }
            case "manage_knowledge" -> {
                actionType = "knowledge_manage";
                JsonNode a = parsed.arguments();
                String action = parsed.action() == null ? "" : parsed.action();
                target = parsed.input().target() == null ? "知识库" : parsed.input().target();
                if ("index".equals(action)) {
                    detail = "文件 id: " + a.path("fileId").asText("?")
                            + (a.path("name").asText("").isBlank() ? "" : "\n展示名: " + a.path("name").asText(""))
                            + "\n后果: 文件内容将被切分+嵌入,加入可检索知识库(可在知识库页删除)";
                    risk = "将把该文件的内容加入知识库索引(可逆:之后可删除文档)";
                } else if ("remove".equals(action)) {
                    detail = "文档 id: " + target + "\n后果: 文档及其全部分块/向量一并删除";
                    risk = "删除后该文档不再可检索,不可自动撤销(文件中心原文件不受影响)";
                } else if ("disable".equals(action) || "enable".equals(action)) {
                    detail = "文档 id: " + target
                            + ("disable".equals(action)
                                ? "\n后果: 文档退出检索(AI 不再引用),数据与索引保留,可随时重新启用"
                                : "\n后果: 文档重新参与检索");
                    risk = "disable".equals(action)
                            ? "停用后 AI 检索不到该文档(可逆:enable 恢复)"
                            : "重新启用该文档参与检索";
                } else {
                    risk = "对知识库执行 " + action + " 操作";
                }
            }
            case "manage_automation" -> {
                actionType = "automation_manage";
                JsonNode a = parsed.arguments();
                String action = parsed.action() == null ? "" : parsed.action();
                target = parsed.input().target() == null ? "新自动任务" : parsed.input().target();
                if ("create".equals(action)) {
                    detail = "规则名: " + a.path("name").asText("?")
                            + "\n触发: " + a.path("triggerType").asText("daily")
                            + "\n无人值守指令: " + a.path("prompt").asText("(空)")
                            + "\n后果: 到点后由无人值守通道自动执行该指令(没有审批门)";
                    risk = "该指令未来将在无人值守通道自动执行——写清楚动作与范围,执行时没有人可以追问";
                } else if ("remove".equals(action)) {
                    detail = "规则 id: " + target + "\n后果: 规则删除,不再触发(执行历史保留)";
                    risk = "删除后该规则不再执行,需重新创建才能恢复";
                } else if ("run".equals(action)) {
                    detail = "规则 id: " + target + "\n后果: 立即执行一次(可能跑数分钟,消耗 LLM 调用)";
                    risk = "立即触发一次自动任务执行";
                } else {
                    risk = "对自动任务执行 " + action + " 操作";
                }
            }
            case "manage_file", "read_file" -> {
                // 文件中心管理动作(2026-09-18 补齐;2026-09-20 更名 manage_file,
                // read_file 兼容别名)的审批明细;list/read 是 LOW 不会走到这
                actionType = "file_manage";
                JsonNode a = parsed.arguments();
                String action = parsed.action() == null ? "" : parsed.action().toLowerCase();
                target = parsed.input().target() == null ? "文件中心" : parsed.input().target();
                switch (action) {
                    case "rename" -> {
                        detail = "文件 id: " + target + "\n新名称: " + a.path("name").asText("?");
                        risk = "将重命名文件中心里的文件";
                    }
                    case "move" -> {
                        detail = "文件 id: " + target + "\n目标文件夹: "
                                + (a.path("folderId").isNumber() ? "id=" + a.path("folderId").asLong() : "根目录");
                        risk = "将移动文件到文件夹";
                    }
                    case "delete" -> {
                        detail = "文件 id: " + target + "\n后果: 移入回收站(可恢复;彻底删除需在文件页操作)";
                        risk = "删除的文件进入回收站,可在文件页恢复";
                    }
                    case "mkdir" -> {
                        detail = "新文件夹: " + a.path("name").asText("?");
                        risk = "将新建一个文件夹";
                    }
                    default -> {
                        detail = "操作: " + action;
                        risk = "对文件中心执行 " + action + " 操作";
                    }
                }
            }
            case "manage_workspace" -> {
                actionType = "workspace_file";
                JsonNode a = parsed.arguments();
                String action = RiskClassifier.normalizeWorkspaceAction(parsed.action());
                // 寻址与风险判定/执行层同一别名序(2026-09-20 统一);list 的 dir 仅作展示兜底
                String p = RiskClassifier.workspacePathOf(a);
                if (p == null || p.isBlank()) {
                    p = a.path("dir").asText(null);
                }
                target = p == null ? "工作区" : p;
                detail = "操作: " + action + " | 路径: " + target
                        + (a.has("content")
                        ? " | 内容预览: " + Texts.abbreviate(a.path("content").asText(""), 200) : "")
                        + (a.has("old_string")
                        ? " | 替换预览: " + Texts.abbreviate(a.path("old_string").asText(""), 120)
                        + " → " + Texts.abbreviate(a.path("new_string").asText(""), 120) : "");
                // 风险文案按真实判定区分(2026-09-19 修复):此前一律硬编码
                // "在工作区之外"——相对路径(区内 LOW 风险)也被这样展示,误导用户;
                // 区内/区外分别给准确描述(move/copy 需看 to 字段)
                String to = a.path("to").asText(null);
                boolean outside = toolExecutor.isOutsideWorkspace(p)
                        || ("move".equals(action) || "copy".equals(action))
                        && toolExecutor.isOutsideWorkspace(to);
                risk = outside
                        ? "该路径在工作区之外——将改动本机的真实文件(不可自动撤销)"
                        : "工作区内文件操作(可回退)";
            }
            default -> {
                if (toolName.startsWith("mcp__")) {
                    actionType = "mcp_tool";
                    // 畸形挂载名(LLM 幻觉出 mcp__srv 缺第二个 __)不能让 substring 越界
                    int sep = toolName.indexOf("__", "mcp__".length());
                    String serverName = sep < 0 ? "?" : toolName.substring("mcp__".length(), sep);
                    // 手机相册整理(2026-09-20):按动作给人类可读明细——用户审的就是
                    // 将要执行的操作(移动/删除/彻底删除等),不用猜参数 JSON
                    if (toolName.endsWith("__photos_manage")) {
                        JsonNode a = parsed.arguments();
                        String action = a.path("action").asText("?").trim().toLowerCase();
                        String ids = a.path("ids").asText("");
                        String album = a.path("album").asText("");
                        target = "手机相册(" + (ids.isBlank() ? "—" : ids) + ")";
                        detail = switch (action) {
                            case "move" -> "把 " + ids + " 移到相册「" + album + "」(可在系统相册里看到)";
                            case "copy" -> "把 " + ids + " 复制到相册「" + album + "」(原件保留)";
                            case "rename" -> "把 " + ids + " 重命名为「" + a.path("name").asText("?") + "」";
                            case "delete" -> "把 " + ids + " 移入 App 回收站(相册里不再显示,可恢复)";
                            case "trash_restore" -> "从回收站恢复 " + ids + " 到 Pictures/Nora 恢复/";
                            case "trash_purge" -> "彻底删除回收站中的 " + ids + "(不可恢复!)";
                            case "trash_list" -> "查看回收站内容";
                            case "album_create" -> "新建相册「" + album + "」并移入 " + ids;
                            default -> "相册整理操作: " + action + " ids=" + ids;
                        };
                        risk = switch (action) {
                            case "trash_purge" -> "彻底删除手机上的照片/视频,不可恢复(仅回收站内文件可被彻底删除)";
                            case "delete" -> "照片移入回收站(可在手机上恢复;不会立即销毁)";
                            case "move", "rename" -> "将改动手机相册里的文件组织(可在系统相册中改回)";
                            default -> "将操作手机相册中的文件";
                        };
                    } else {
                        target = "MCP 服务器 " + serverName;
                        detail = "工具: " + toolName + "\n参数: " + Texts.abbreviate(rawArgs == null ? "{}" : rawArgs, 400);
                        risk = "外部 MCP 服务器提供的工具,能力未知,执行前需确认";
                    }
                } else {
                    actionType = toolName;
                    target = parsed.input().service() != null ? parsed.input().service()
                            : (parsed.input().sql() != null ? Texts.abbreviate(parsed.input().sql(), 40) : "—");
                    risk = "该档位下每次工具调用都需要确认";
                }
            }
        }
        return toolExecutor.redactValue(new ApprovalRequestDto(null, stepId, actionType, target,
                defaultTitle(toolName) + " · " + target, risk, detail), ApprovalRequestDto.class);
    }

    /** 解析审批明细用的 args JSON;失败返回空节点(明细降级为不含参数)。 */
    JsonNode parseArgsSafe(String rawArgs) {
        try {
            JsonNode node = objectMapper.readTree(rawArgs == null ? "{}" : rawArgs);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 暴露行数供 UI 折叠预览("21 lines of output")。 */
    private static Integer countLines(String content) {
        if (content == null) return null;
        return content.split("\n", -1).length;
    }

    /** 把脱敏后的原始参数附到 typed input 上(跨轮历史重建用;null/空/非法 = 不附)。 */
    ChatStepDto.StepInput withRawArgs(ChatStepDto.StepInput input, String args, String name) {
        if (input == null || args == null || args.isBlank()) {
            return input;
        }
        try {
            // 只有合法 JSON 对象才能作为 tool_calls.arguments 重放
            if (!objectMapper.readTree(args).isObject()) {
                return input;
            }
        } catch (Exception e) {
            return input;
        }
        String raw = scrubArgsForLog(args, name);
        if (raw.length() > MAX_REPLAY_ARGS_CHARS) {
            return input;
        }
        return new ChatStepDto.StepInput(input.sql(), input.service(), input.limit(), input.target(), raw);
    }

    String scrubArgsForLog(String args, String name) {
        if (args == null || args.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(args);
            if (!node.isContainerNode()) return "{}";
            if ("manage_env_vars".equals(name) && node instanceof ObjectNode obj) {
                String value = obj.path("value").asText("");
                if (obj.has("value")) obj.put("value", "***");
                obj.remove("description");
                if (!value.isBlank() && obj.path("note").isTextual()) {
                    obj.put("note", obj.path("note").asText().replace(value, "***"));
                }
            }
            toolExecutor.scrubCredentials(node);
            return node.toString();
        } catch (Exception e) {
            return "{}"; // 非法 JSON 也可能包含凭据,不写入日志或历史。
        }
    }
}
