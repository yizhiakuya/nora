package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 工具执行器(从 ChatOrchestrationService 拆出,2026-09-17 复杂度审计 Step 2):
 * executeTool 的 11 路分发 + 结果裁剪(bounded)+ 守卫(guardSql/guardService)
 * + URL 导入(importFromUrl/importToWorkbenchFile/downloadBounded)。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class ChatToolExecutor {

    private final ObjectMapper objectMapper;
    private final SqlToolClient sqlToolClient;
    private final ServiceLogClient serviceLogClient;
    private final WriteSqlClient writeSqlClient;
    private final ContainerControlClient containerControlClient;
    private final DataSourceManageClient dataSourceManageClient;
    private final ServiceManageClient serviceManageClient;
    private final FileToolClient fileToolClient;
    private final McpServerService mcpServerService;
    private final TerminalService terminalService;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSkillService agentSkillService;

    ChatToolExecutor(ObjectMapper objectMapper,
                     SqlToolClient sqlToolClient,
                     ServiceLogClient serviceLogClient,
                     WriteSqlClient writeSqlClient,
                     ContainerControlClient containerControlClient,
                     DataSourceManageClient dataSourceManageClient,
                     ServiceManageClient serviceManageClient,
                     FileToolClient fileToolClient,
                     McpServerService mcpServerService,
                     TerminalService terminalService,
                     AgentWorkspaceService agentWorkspaceService,
                     AgentSkillService agentSkillService) {
        this.objectMapper = objectMapper;
        this.sqlToolClient = sqlToolClient;
        this.serviceLogClient = serviceLogClient;
        this.writeSqlClient = writeSqlClient;
        this.containerControlClient = containerControlClient;
        this.dataSourceManageClient = dataSourceManageClient;
        this.serviceManageClient = serviceManageClient;
        this.fileToolClient = fileToolClient;
        this.mcpServerService = mcpServerService;
        this.terminalService = terminalService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
    }

    /** 技能定位:target 是数字 → 按 id,否则按名称(不区分大小写)。 */
    AgentSkillService.SkillView resolveSkill(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String trimmed = target.trim();
        if (trimmed.matches("\\d+")) {
            AgentSkillService.SkillView byId = agentSkillService.get(Long.parseLong(trimmed));
            if (byId != null) {
                return byId;
            }
        }
        return agentSkillService.getByName(trimmed);
    }

    String skillNameList() {
        List<AgentSkillService.SkillView> all = agentSkillService.list();
        if (all.isEmpty()) {
            return "(暂无技能)";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentSkillService.SkillView s : all) {
            sb.append("- ").append(s.name()).append(s.enabled() ? "" : " [已停用]").append('\n');
        }
        return sb.toString();
    }

    /**
     * Tool output budget per harness research: success keeps a large inline
     * budget, failure gets a head+tail excerpt (failures are diagnosable from
     * the ends alone and never need a persistence pointer).
     */
    static final int MAX_SUCCESS_CHARS = 30_000;
    static final int MAX_FAILURE_CHARS = 10_000;
    /** How much of the head/tail a failure excerpt keeps. */
    private static final int FAILURE_HEAD_CHARS = 6_000;
    private static final int FAILURE_TAIL_CHARS = 3_000;

    /**
     * Dispatches a tool call. Guardrail rejections return a three-part error
     * (what was refused + which rule + a correct example) so the model can
     * self-correct on the next round.
     */
    /**
     * Dispatches a tool call. Guardrail rejections return a three-part error
     * (what was refused + which rule + a correct example) so the model can
     * self-correct on the next round.
     *
     * <p>2026-09-17:各工具分支拆为独立 handler(见下方 exec* 方法),此处只做分发。
     */
    ToolOutcome executeTool(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                                    java.util.function.Consumer<String> liveOutput) {
        if ("execute_sql".equals(name)) {
            return execExecuteSql(name, args, parsed, liveOutput);
        }
        if ("read_service_logs".equals(name)) {
            return execReadServiceLogs(name, args, parsed, liveOutput);
        }
        if ("execute_write_sql".equals(name)) {
            return execExecuteWriteSql(name, args, parsed, liveOutput);
        }
        if ("manage_container".equals(name)) {
            return execManageContainer(name, args, parsed, liveOutput);
        }
        if ("manage_datasource".equals(name)) {
            return execManageDatasource(name, args, parsed, liveOutput);
        }
        if ("manage_service".equals(name)) {
            return execManageService(name, args, parsed, liveOutput);
        }
        if ("read_file".equals(name)) {
            return execReadFile(name, args, parsed, liveOutput);
        }
        if ("manage_workspace".equals(name)) {
            return execManageWorkspace(name, args, parsed, liveOutput);
        }
        if ("manage_skill".equals(name)) {
            return execManageSkill(name, args, parsed, liveOutput);
        }
        if ("manage_mcp".equals(name)) {
            return execManageMcp(name, args, parsed, liveOutput);
        }
        if ("run_command".equals(name)) {
            return execRunCommand(name, args, parsed, liveOutput);
        }
        // MCP 挂载工具兜底分发:名字带 mcp__ 前缀 → 路由到对应服务器执行;
        // 输出同样走 bounded 截断与脱敏
        if (mcpServerService != null && name.startsWith("mcp__")) {
            McpServerService.RawServer server = mcpServerService.serverForMountedTool(name);
            if (server == null) {
                return new ToolOutcome("ERROR: 找不到该工具对应的 MCP 服务器(可能已被禁用或删除): " + name,
                        null, null, false);
            }
            McpServerService.McpToolResult mcpResult =
                    mcpServerService.callToolRich(server.id(), McpServerService.rawToolName(name), args);
            ToolOutcome mcpOutcome = bounded(mcpResult.text(), "MCP " + server.name() + " 执行完成");
            // 保留 image 块:图片本体不参与文本截断(避免把 base64 当文本切)，
            // 由回填层按模型识图能力决定是否附上
            return mcpResult.images().isEmpty() ? mcpOutcome
                    : new ToolOutcome(mcpOutcome.content(), mcpOutcome.summary(), mcpOutcome.rowCount(),
                            mcpOutcome.truncated(), mcpResult.images());
        }
        return new ToolOutcome("ERROR: unknown tool " + name
                + ". 可用工具：execute_sql（只读 SQL,可选 datasource 参数）、execute_write_sql（写 SQL,需批准）、"
                + "read_service_logs（容器日志）、manage_container（容器启停,需批准）、"
                + "manage_datasource（数据源 list/create/test/schema/remove,create/remove 需批准）、"
                + "manage_service（纳管源 list/register/enable/disable/remove,register/remove 需批准）、"
                + "read_file（工作台文件 list/read,只读）、"
                + "manage_workspace（工作区文件 list/read/write/append/delete）、"
                + "manage_skill（技能 list/read/create/update/remove）、"
                + "manage_mcp（MCP 服务器 list/refresh/enable/disable/register/remove,风险跟随权限档位）、"
                + "run_command（本机终端非交互命令,风险跟随权限档位）"
                + (name.startsWith("mcp__") ? " 或已挂载的 MCP 工具(mcp__<server>__<tool>)" : ""), null, null, false);
    }

    /** execute_sql handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execExecuteSql(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String sql = parsed.input().sql() != null ? parsed.input().sql() : "";
        String guard = guardSql(sql);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        SqlToolClient.SqlOutcome outcome = sqlToolClient.executeSqlDetailed(sql, parsed.input().target());
        return new ToolOutcome(outcome.content(), outcome.summary(), null, outcome.truncated());
    }

    /** read_service_logs handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execReadServiceLogs(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String service = parsed.input().service() != null ? parsed.input().service() : "";
        int limit = parsed.input().limit() != null ? Math.min(parsed.input().limit(), 100) : 50;
        String guard = guardService(service);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        return bounded(serviceLogClient.readLogs(service, limit), null);
    }

    /** execute_write_sql handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execExecuteWriteSql(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String sql = parsed.input().sql() != null ? parsed.input().sql() : "";
        String guard = RiskClassifier.validateWriteSql(sql);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        if (writeSqlClient == null) {
            return new ToolOutcome("ERROR: 写入能力未启用(服务未配置)", null, null, false);
        }
        String content = writeSqlClient.executeWrite(sql, parsed.input().target());
        boolean failure = content.startsWith("ERROR:");
        return new ToolOutcome(content, failure ? null : content, null, false);
    }

    /** manage_container handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageContainer(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String service = parsed.input().service() == null ? "" : parsed.input().service();
        String guard = RiskClassifier.validateContainerAction(parsed.containerAction());
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        String guardService = guardService(service);
        if (guardService != null) {
            return new ToolOutcome("ERROR: " + guardService, null, null, false);
        }
        if (!serviceLogClient.listServices().contains(service)) {
            return new ToolOutcome("ERROR: unknown service " + service + ". 可用服务必须来自环境服务注册表", null, null, false);
        }
        if (containerControlClient == null) {
            return new ToolOutcome("ERROR: 容器控制能力未启用(服务未配置)", null, null, false);
        }
        String content = containerControlClient.control(service, parsed.containerAction());
        boolean failure = content.startsWith("ERROR:");
        return new ToolOutcome(content, failure ? null : content, null, false);
    }

    /** manage_datasource handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageDatasource(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
        String guard = RiskClassifier.validateDatasourceAction(action);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        if ("list".equals(action)) {
            return bounded(dataSourceManageClient.list(), null);
        }
        if ("schema".equals(action)) {
            // schema 不在工具 spec 里宣传,但模型从 list 结果推断时放行(只读)
            return bounded(dataSourceManageClient.schema(parsed.input().target()), null);
        }
        if ("create".equals(action)) {
            // 连接参数从原始 args 取(密码只 here 使用,不进 ParsedArgs/步骤记录)
            JsonNode a;
            try {
                a = objectMapper.readTree(args == null ? "{}" : args);
            } catch (Exception e) {
                return new ToolOutcome("ERROR: 参数不是合法 JSON: " + e.getMessage(), null, null, false);
            }
            String dName = a.path("name").asText(null);
            String engine = a.path("engine").asText(null);
            String host = a.path("host").asText(null);
            Integer port = a.path("port").isInt() ? a.path("port").asInt() : null;
            String database = a.path("database").asText(null);
            String username = a.path("username").asText(null);
            String password = a.path("password").asText(null);
            String createGuard = RiskClassifier.validateDatasourceCreate(engine, host, port, database);
            if (createGuard != null) {
                return new ToolOutcome("ERROR: " + createGuard, null, null, false);
            }
            if (dName == null || dName.isBlank()) {
                return new ToolOutcome("ERROR: 缺少 name 参数(连接显示名,如 \"订单库-生产\")", null, null, false);
            }
            String content = dataSourceManageClient.create(dName, engine, host, port, database, username, password);
            boolean failure = content.startsWith("ERROR:");
            return new ToolOutcome(content, failure ? null : summarizeCreate(content), null, false);
        }
        // test / remove:目标 = name 或数字 id
        String target = parsed.input().target();
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: 缺少目标数据源(name 或 id)。可先用 action=list 查看", null, null, false);
        }
        Long connectionId = sqlToolClient.resolveConnectionId(target);
        if (connectionId == null) {
            return new ToolOutcome("ERROR: 找不到数据源 \"" + target + "\"。可用连接:\n" + dataSourceManageClient.list(),
                    null, null, false);
        }
        String content = "test".equals(action)
                ? dataSourceManageClient.test(connectionId)
                : dataSourceManageClient.remove(connectionId);
        boolean failure = content.startsWith("ERROR:");
        return new ToolOutcome(content, failure ? null : content, null, false);
    }

    /** manage_service handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageService(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
        String guard = RiskClassifier.validateServiceAction(action);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        if ("list".equals(action)) {
            return bounded(serviceManageClient.list(), null);
        }
        if ("register".equals(action)) {
            JsonNode a;
            try {
                a = objectMapper.readTree(args == null ? "{}" : args);
            } catch (Exception e) {
                return new ToolOutcome("ERROR: 参数不是合法 JSON: " + e.getMessage(), null, null, false);
            }
            String kind = a.path("kind").asText(null);
            String sName = a.path("name").asText(null);
            String fileLogPath = a.path("fileLogPath").asText(null);
            String containerName = a.path("containerName").asText(null);
            String command = a.path("command").asText(null);
            String workDir = a.path("workDir").asText(null);
            String registerGuard = RiskClassifier.validateServiceRegister(kind, fileLogPath, containerName, command);
            if (registerGuard != null) {
                return new ToolOutcome("ERROR: " + registerGuard, null, null, false);
            }
            if (sName == null || sName.isBlank()) {
                return new ToolOutcome("ERROR: 缺少 name 参数(纳管源显示名)", null, null, false);
            }
            String content = serviceManageClient.register(kind, sName, fileLogPath, containerName, command, workDir);
            boolean failure = content.startsWith("ERROR:");
            return new ToolOutcome(content, failure ? null : content, null, false);
        }
        // enable / disable / remove:目标 = name 或数字 id
        String target = parsed.input().target();
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: 缺少目标纳管源(name 或 id)。可先用 action=list 查看", null, null, false);
        }
        Long sourceId = resolveManagedSourceId(target);
        if (sourceId == null) {
            return new ToolOutcome("ERROR: 找不到纳管源 \"" + target + "\"。可用纳管源:\n" + serviceManageClient.list(),
                    null, null, false);
        }
        String content = switch (action) {
            case "enable" -> serviceManageClient.setEnabled(sourceId, true);
            case "disable" -> serviceManageClient.setEnabled(sourceId, false);
            default -> serviceManageClient.remove(sourceId);
        };
        boolean failure = content.startsWith("ERROR:");
        return new ToolOutcome(content, failure ? null : content, null, false);
    }

    /** read_file handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execReadFile(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        String action = parsed.datasourceAction() == null ? "list" : parsed.datasourceAction().trim().toLowerCase();
        // import: 把远程 URL 下载并存成工作台文件(用户可见可管理)
        if ("import".equals(action)) {
            String r = importToWorkbenchFile(args);
            return r.startsWith("ERROR:") ? new ToolOutcome(r, null, null, false)
                    : new ToolOutcome(r, r, null, false);
        }
        if ("list".equals(action) || parsed.input().target() == null) {
            // 无 id = 列出文件让模型挑;显式 action=list 同理
            return bounded(fileToolClient.list(), null);
        }
        String target = parsed.input().target();
        if (!target.matches("\\d+")) {
            return new ToolOutcome("ERROR: id 必须是数字(先用 action=list 查看可用文件)"
                    + ",不能按文件名猜测。当前收到: " + target, null, null, false);
        }
        long fileId = Long.parseLong(target);
        FileToolClient.PreviewInfo info = fileToolClient.previewInfo(fileId);
        if (info.failed()) {
            return bounded("ERROR: " + info.error(), null);
        }
        if (info.hasText()) {
            return bounded(fileToolClient.renderPreview(info), null);
        }
        // 无文本=二进制/图片:图片走图像通道(视觉模型直接看图;
        // 非视觉模型由 backfillToolMessage 明确告知看不到,不静默丢弃)
        JsonNode meta = fileToolClient.meta(fileId);
        String mime = meta == null ? null : meta.path("mimeType").asText(null);
        if (mime != null && mime.startsWith("image/")) {
            FileToolClient.RawFile raw = fileToolClient.raw(fileId);
            if (raw != null && raw.bytes().length > 0) {
                String b64 = java.util.Base64.getEncoder().encodeToString(raw.bytes());
                String desc = "图片文件 " + info.name() + "(" + FileToolClient.formatSize(raw.bytes().length)
                        + ", " + mime + "),原始字节已作为图像附件返回;直接描述你看到的内容";
                return new ToolOutcome(desc, "图片", null, false,
                        List.of(new McpServerService.McpToolResult.ImageBlock(mime, b64)));
            }
        }
        return bounded("文件 " + info.name() + " 没有可提取的文本内容(可能是二进制/图片)", null);
    }

    /** manage_workspace handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageWorkspace(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        if (agentWorkspaceService == null) {
            return new ToolOutcome("ERROR: 工作区能力未启用(服务未配置)", null, null, false);
        }
        String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
        if (!java.util.Set.of("list", "read", "write", "append", "delete", "import").contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 list / read / write / append / delete / import",
                    null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            String path = a.path("path").asText(null);
            // read 图片:文本解码必然失败("Input length = 1"),改走图像通道——
            // 原始字节作为图像附件喂给视觉模型;非视觉模型由 backfillToolMessage
            // 明确告知「看不到」,不静默丢弃、不报解码错误。
            if ("read".equals(action) && path != null && !path.isBlank()) {
                String imgMime = AgentWorkspaceService.imageMime(path);
                if (imgMime != null) {
                    try {
                        byte[] bytes = agentWorkspaceService.readBytesAny(path);
                        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
                        return new ToolOutcome(
                                "图片文件 " + path + "(" + FileToolClient.formatSize(bytes.length)
                                        + ", " + imgMime + "),原始字节已作为图像附件返回;直接描述你看到的内容",
                                "图片", null, false,
                                List.of(new McpServerService.McpToolResult.ImageBlock(imgMime, b64)));
                    } catch (IllegalArgumentException e) {
                        return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
                    }
                }
            }
            return new ToolOutcome(switch (action) {
                case "list" -> {
                    // dir 优先,其次 path(agent 可能把路径塞进 path)
                    String dirArg = a.path("dir").asText(null);
                    if (dirArg == null) {
                        dirArg = a.path("path").asText(null);
                    }
                    List<AgentWorkspaceService.FileEntry> entries =
                            agentWorkspaceService.listAny(dirArg);
                    if (entries.isEmpty()) {
                        yield "(空目录)";
                    }
                    StringBuilder sb = new StringBuilder("工作区文件(" + path + " 相对根目录):\n");
                    for (AgentWorkspaceService.FileEntry f : entries) {
                        sb.append(f.directory() ? "[目录] " : "").append(f.path())
                                .append(f.directory() ? "" : " (" + f.size() + "B, " + f.modifiedAt() + ")")
                                .append('\n');
                    }
                    yield sb.toString();
                }
                case "read" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数。相对路径=工作区内(如 USER.md);绝对路径可读整机(如 D:/projects/x/README.md)";
                    }
                    yield agentWorkspaceService.readAny(path);
                }
                case "write" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数(相对路径)";
                    }
                    String content = a.path("content").asText(null);
                    if (content == null) {
                        yield "ERROR: 缺少 content 参数(要写入的完整内容;如需保留原内容请先 read)";
                    }
                    int written = agentWorkspaceService.writeAny(path, content);
                    yield "已写入 " + path + "(" + written + " 字符)";
                }
                case "append" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数(相对路径)";
                    }
                    String content = a.path("content").asText(null);
                    if (content == null || content.isBlank()) {
                        yield "ERROR: 缺少 content 参数(要追加的内容)";
                    }
                    int written = agentWorkspaceService.appendAny(path, content);
                    yield "已追加 " + written + " 字符到 " + path;
                }
                case "import" -> importFromUrl(a, path);
                default -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数;删除不可恢复,请先向用户确认";
                    }
                    agentWorkspaceService.deleteAny(path);
                    yield "已删除 " + path;
                }
            }, null, null, false);
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 工作区操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** manage_skill handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageSkill(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        if (agentSkillService == null) {
            return new ToolOutcome("ERROR: 技能能力未启用(服务未配置)", null, null, false);
        }
        String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
        if (!java.util.Set.of("list", "read", "create", "update", "remove").contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 list / read / create / update / remove", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            return new ToolOutcome(switch (action) {
                case "list" -> {
                    List<AgentSkillService.SkillView> all = agentSkillService.list();
                    if (all.isEmpty()) {
                        yield "(暂无技能)";
                    }
                    StringBuilder sb = new StringBuilder("现有技能:\n");
                    for (AgentSkillService.SkillView s : all) {
                        sb.append("id=").append(s.id())
                                .append(s.enabled() ? "" : " [已停用]")
                                .append(" [").append(s.category()).append("] ")
                                .append(s.name()).append(": ").append(s.description()).append('\n');
                    }
                    yield sb.toString();
                }
                case "read" -> {
                    String target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
                    AgentSkillService.SkillView skill = resolveSkill(target);
                    if (skill == null) {
                        yield "ERROR: 找不到技能「" + target + "」。可用技能:\n" + skillNameList();
                    }
                    yield "技能「" + skill.name() + "」完整指令:\n" + skill.instructions();
                }
                case "create" -> {
                    String sName = a.path("name").asText(null);
                    String instr = a.path("instructions").asText(null);
                    if (sName == null || sName.isBlank()) {
                        yield "ERROR: 缺少 name 参数(技能名称)";
                    }
                    if (instr == null || instr.isBlank()) {
                        yield "ERROR: 缺少 instructions 参数(技能正文)";
                    }
                    if (agentSkillService.getByName(sName.trim()) != null) {
                        yield "ERROR: 技能名「" + sName.trim() + "」已存在。如需修改用 action=update";
                    }
                    AgentSkillService.SkillView created = agentSkillService.create(
                            sName, a.path("description").asText(null), instr, a.path("category").asText(null));
                    yield "已创建技能(id=" + created.id() + "): " + created.name();
                }
                case "update" -> {
                    String target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
                    AgentSkillService.SkillView skill = resolveSkill(target);
                    if (skill == null) {
                        yield "ERROR: 找不到技能「" + target + "」。可用技能:\n" + skillNameList();
                    }
                    Boolean enabled = a.has("enabled") ? a.path("enabled").asBoolean() : null;
                    AgentSkillService.SkillView updated = agentSkillService.update(skill.id(),
                            a.has("name") ? a.path("name").asText(null) : null,
                            a.has("description") ? a.path("description").asText(null) : null,
                            a.has("instructions") ? a.path("instructions").asText(null) : null,
                            a.has("category") ? a.path("category").asText(null) : null,
                            enabled);
                    yield "已更新技能(id=" + updated.id() + ", " + (updated.enabled() ? "启用" : "停用") + "): " + updated.name();
                }
                default -> {
                    String target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
                    AgentSkillService.SkillView skill = resolveSkill(target);
                    if (skill == null) {
                        yield "ERROR: 找不到技能「" + target + "」。可用技能:\n" + skillNameList();
                    }
                    yield agentSkillService.delete(skill.id()) ? "已删除技能: " + skill.name() : "ERROR: 删除失败";
                }
            }, null, null, false);
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return new ToolOutcome("ERROR: 技能名已存在,先 action=list 查看现有技能", null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 技能操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** manage_mcp handler(2026-09-17 从 executeTool 拆出;内部再拆 list/register/目标操作三段)。 */
    ToolOutcome execManageMcp(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        if (mcpServerService == null) {
            return new ToolOutcome("ERROR: MCP 管理能力未启用(服务未配置)", null, null, false);
        }
        // 与分类器共用归一化:create/delete 等别名 → register/remove(两处必须一致)
        String action = RiskClassifier.normalizeMcpAction(parsed.datasourceAction());
        String guard = RiskClassifier.validateMcpAction(action);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            if ("list".equals(action)) {
                return mcpListResult();
            }
            if ("register".equals(action)) {
                return mcpRegisterResult(a);
            }
            return mcpTargetOpResult(action, a);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // create/refresh 的参数与连接错误:直接作为可自纠错误回给模型
            return new ToolOutcome("ERROR: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 300),
                    null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: MCP 操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** manage_mcp action=list:服务器清单(含状态/工具数)。 */
    private ToolOutcome mcpListResult() {
        List<McpServerService.ServerView> all = mcpServerService.list();
        if (all.isEmpty()) {
            return new ToolOutcome("(暂无 MCP 服务器)。可用 action=register 注册:"
                    + "{\"action\": \"register\", \"name\": \"名称\", \"url\": \"https://...\"}",
                    null, null, false);
        }
        StringBuilder sb = new StringBuilder("已注册 MCP 服务器:\n");
        for (McpServerService.ServerView s : all) {
            sb.append("id=").append(s.id())
                    .append(s.enabled() ? "" : " [已停用]")
                    .append(" ").append(s.name())
                    .append(" · ").append(s.transport())
                    .append(" · ").append(s.status())
                    .append(s.statusDetail() != null ? "(" + Texts.abbreviate(s.statusDetail(), 80) + ")" : "")
                    .append(" · 工具数 ").append(s.toolCount())
                    .append('\n');
        }
        return new ToolOutcome(sb.toString(), null, null, false);
    }

    /** manage_mcp action=register:注册 + 自动测试连接(失败不回滚注册)。 */
    private ToolOutcome mcpRegisterResult(JsonNode a) {
        String rName = a.path("name").asText(null);
        String rUrl = a.path("url").asText(null);
        String rTransport = a.path("transport").asText(null);
        // STDIO(本地进程)注册:command + args + env
        String rCommand = a.path("command").asText(null);
        // 推断:给了 command 没给 url/transport → 本地进程形态(模型常省略 transport)
        if ((rTransport == null || rTransport.isBlank())
                && rCommand != null && !rCommand.isBlank()
                && (rUrl == null || rUrl.isBlank())) {
            rTransport = "STDIO";
        }
        List<String> rArgs = new java.util.ArrayList<>();
        if (a.path("args").isArray()) {
            for (JsonNode n : a.path("args")) {
                rArgs.add(n.asText(""));
            }
        }
        String registerGuard = RiskClassifier.validateMcpRegister(rName, rUrl, rTransport, rCommand, rArgs);
        if (registerGuard != null) {
            return new ToolOutcome("ERROR: " + registerGuard, null, null, false);
        }
        if (mcpServerService.findByName(rName) != null) {
            return new ToolOutcome("ERROR: 服务器名「" + rName.trim() + "」已存在。如需改用 remove 后重新注册,"
                    + "或用 refresh 重新拉取工具", null, null, false);
        }
        // headers/env 从原始 args 取,只传给服务层——值不进步骤/审批/对话记录
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        JsonNode h = a.path("headers");
        if (h.isObject()) {
            h.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText("")));
        }
        Map<String, String> env = new java.util.LinkedHashMap<>();
        JsonNode envNode = a.path("env");
        if (envNode.isObject()) {
            envNode.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText("")));
        }
        McpServerService.ServerView created = mcpServerService.create(rName, rUrl, rTransport,
                headers.isEmpty() ? null : headers,
                rCommand, rArgs.isEmpty() ? null : rArgs, env.isEmpty() ? null : env);
        // 注册后自动测试连接(refresh):对齐 manage_datasource create 后自动 test 的语义;
        // 连接失败不回滚注册(注册本身成功,失败原因如实报告,用户可稍后重试 refresh)
        String testResult;
        try {
            List<McpServerService.ToolEntry> toolEntries = mcpServerService.refresh(created.id());
            testResult = "连接成功,发现 " + toolEntries.size() + " 个工具"
                    + (toolEntries.isEmpty() ? "" : ": " + formatToolNames(toolEntries)
                    + (toolEntries.size() > 10 ? " 等" : ""))
                    + "。工具已挂载(mcp__" + created.name() + "__*),下轮对话可直接调用";
        } catch (Exception e) {
            testResult = "连接测试失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200)
                    + "(注册已保留;可检查地址/鉴权后用 refresh 重试)";
        }
        return new ToolOutcome("已注册 MCP 服务器(id=" + created.id() + "): " + created.name()
                + " · " + created.transport() + "\n" + testResult, null, null, false);
    }

    /** manage_mcp 目标操作:refresh / enable / disable / remove(目标 = 名称或数字 id)。 */
    private ToolOutcome mcpTargetOpResult(String action, JsonNode a) {
        // refresh / enable / disable / remove:目标 = 名称或数字 id
        String target = Texts.firstNonNull(a.path("target").asText(null), a.path("name").asText(null));
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: 缺少目标服务器(target = 名称或 id)。可先用 action=list 查看",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器「" + target + "」。可先用 action=list 查看现有服务器"
                    + "(服务器名不能猜测)", null, null, false);
        }
        String content = switch (action) {
            case "refresh" -> {
                List<McpServerService.ToolEntry> toolEntries = mcpServerService.refresh(server.id());
                yield "已连接「" + server.name() + "」,发现 " + toolEntries.size() + " 个工具"
                        + (toolEntries.isEmpty() ? "" : ": " + formatToolNames(toolEntries)
                        + (toolEntries.size() > 10 ? " 等" : ""));
            }
            case "enable" -> mcpServerService.setEnabled(server.id(), true)
                    ? "已启用「" + server.name() + "」。工具将在下轮对话挂载(缓存过工具清单则立即可用)"
                    : "ERROR: 启用失败,服务器可能已被删除";
            case "disable" -> mcpServerService.setEnabled(server.id(), false)
                    ? "已停用「" + server.name() + "」。其工具不再挂载"
                    : "ERROR: 停用失败,服务器可能已被删除";
            default -> mcpServerService.delete(server.id())
                    ? "已删除 MCP 服务器「" + server.name() + "」及其连接"
                    : "ERROR: 删除失败,服务器可能已被删除";
        };
        boolean failure = content.startsWith("ERROR:");
        return new ToolOutcome(content, failure ? null : content, null, false);
    }

    /** 工具名清单(最多 10 个,逗号分隔;register/refresh 的结果文案共用)。 */
    private static String formatToolNames(List<McpServerService.ToolEntry> toolEntries) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(toolEntries.size(), 10); i++) {
            names.append(i > 0 ? ", " : "").append(toolEntries.get(i).name());
        }
        return names.toString();
    }

    /** run_command handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execRunCommand(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            java.util.function.Consumer<String> liveOutput) {
        if (terminalService == null) {
            return new ToolOutcome("ERROR: 终端能力未启用(服务未配置)", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            String cmd = a.path("command").asText(null);
            String cwdArg = a.path("cwd").asText(null);
            Integer timeout = a.path("timeout").isInt() ? a.path("timeout").asInt() : null;
            String shell = a.path("shell").asText(null);
            TerminalService.RunResult r = terminalService.run(cmd, cwdArg, timeout, shell, liveOutput);
            // 非零退出码按失败处理(红色步骤 + ERROR 前缀回填模型):
            // 与 Claude Code 同语义——"命令跑了但失败了"不是成功结果;
            // 仍走 bounded():失败预算 10K、成功 30K,且做脱敏
            boolean failure = r.exitCode() != 0 || r.timedOut() || r.cancelled();
            String rendered = r.render();
            return bounded(failure ? "ERROR: " + rendered : rendered, summarizeCommand(r));
        } catch (IllegalArgumentException e) {
            // 参数/启动错误:可自纠错误回给模型
            return new ToolOutcome("ERROR: " + Texts.abbreviate(e.getMessage(), 300), null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 命令执行失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** 命令结果的一行摘要:exit code + 耗时(供折叠行展示)。 */
    private static String summarizeCommand(TerminalService.RunResult r) {
        if (r.cancelled()) {
            return "命令已取消";
        }
        if (r.timedOut()) {
            return "命令超时 " + r.timeoutSec() + "s(已终止)";
        }
        return "exit " + r.exitCode() + ", " + r.durationMs() + "ms";
    }

    /**
     * Guardrail: one read-only statement only. Rejections carry what was
     * refused, which rule, and a correct example.
     */
    static String guardSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "拒绝执行：缺少 sql 参数。该工具需要一条只读 SQL 语句，正确示例：{\"sql\": \"SELECT * FROM orders LIMIT 10\"}";
        }
        String normalized = sql.trim();
        if (!normalized.matches("(?is)^(SELECT|SHOW|EXPLAIN)\\b.*")) {
            return "拒绝执行「" + Texts.abbreviate(normalized, 60) + "」：只允许单条 SELECT / SHOW / EXPLAIN 查询，"
                    + "不允许写入或修改数据。正确示例：{\"sql\": \"SELECT status, COUNT(*) FROM orders GROUP BY status\"}";
        }
        String withoutTrailing = normalized.replaceFirst(";\\s*$", "");
        if (withoutTrailing.contains(";")) {
            return "拒绝执行：一次只允许一条 SQL 语句（检测到多条）。请拆成多次调用，每次一条，"
                    + "示例：{\"sql\": \"SELECT 1\"}";
        }
        if (normalized.length() > 10000) {
            return "拒绝执行：SQL 超过 10000 字符上限（当前 " + normalized.length() + "）。"
                    + "请简化查询或改用视图/子查询减少字面量";
        }
        return null;
    }

    /** Guardrail for the service log tool: service must come from the registry. */
    static String guardService(String service) {
        if (service == null || service.isBlank()) {
            return "拒绝执行：缺少 service 参数。该工具需要容器名，可用值如：nora-postgres、nora-redis、nora-nacos";
        }
        return null;
    }

    /**
     * Bounds a raw tool result to the inline budget. Success keeps up to
     * {@value MAX_SUCCESS_CHARS} chars; anything larger is cut with a
     * truncation marker. Failures (ERROR:) get a head+tail excerpt instead.
     */
    ToolOutcome bounded(String result, String summary) {
        if (result == null) {
            return new ToolOutcome("(empty)", summary, null, false);
        }
        String redacted = result.replaceAll("(?i)(api[_-]?key|password|token|secret)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[REDACTED]");
        boolean failure = redacted.startsWith("ERROR:");
        int budget = failure ? MAX_FAILURE_CHARS : MAX_SUCCESS_CHARS;
        if (redacted.length() <= budget) {
            if (failure) {
                return new ToolOutcome(redacted, summary, null, false);
            }
            return new ToolOutcome(redacted, summary != null ? summary : summarizeRows(redacted), null, false);
        }
        String cut = failure
                ? redacted.substring(0, FAILURE_HEAD_CHARS) + "\n…(中间省略)…\n"
                        + redacted.substring(redacted.length() - FAILURE_TAIL_CHARS)
                : redacted.substring(0, budget) + "\n…(结果已截断,请缩小查询范围,如加 LIMIT 或 WHERE)";
        return new ToolOutcome(cut, summary, null, true);
    }

    /** Derives a one-line row summary from a TSV render ("(3 rows, 12ms)" footer). */
    private String summarizeRows(String tsv) {
        int idx = tsv.lastIndexOf("(");
        if (idx >= 0 && tsv.endsWith(")")) {
            return tsv.substring(idx + 1, tsv.length() - 1);
        }
        return null;
    }

    /**
     * Resolves a managed source name (or numeric id) to its env-service id.
     * Names are matched case-insensitively against the full registry
     * (including paused sources, which /services omits).
     */
    private Long resolveManagedSourceId(String target) {
        String listing = serviceManageClient.list();
        if (listing.startsWith("ERROR") || listing.startsWith("(")) {
            return null;
        }
        String trimmed = target.trim();
        for (String line : listing.split("\n")) {
            // 行形如:id=3 | DOCKER | nora-redis | nora-redis | enabled
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            String id = parts[0].replace("id=", "").trim();
            String kind = parts[1].trim();
            String name = parts[2].trim();
            if (trimmed.equalsIgnoreCase(name) || (trimmed.matches("\\d+") && trimmed.equals(id))) {
                return Long.parseLong(id);
            }
            if ("PROC".equals(kind) && trimmed.equalsIgnoreCase(name)) {
                return Long.parseLong(id);
            }
        }
        return null;
    }

    /** create 结果的摘要行(密码永不回传,渲染端只含连接目标与测试结论)。 */
    private static String summarizeCreate(String content) {
        String firstLine = content.contains("\n") ? content.substring(0, content.indexOf('\n')) : content;
        return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 80);
    }

    /**
     * 把远程 URL 下载并存成工作台「文件」(read_file action=import)。
     *
     * <p>与 manage_workspace 的 import 区别:这里存进用户可见的文件中心
     * (可预览/删除/建索引),适合“把这张图存起来我稍后看”。
     */
    private String importToWorkbenchFile(String args) {
        JsonNode a;
        try {
            a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
        } catch (Exception e) {
            return "ERROR: 参数不是合法 JSON: " + e.getMessage();
        }
        String url = a.path("url").asText(null);
        if (url == null || url.isBlank()) {
            return "ERROR: 缺少 url 参数(要下载的地址)";
        }
        if (!url.matches("(?i)^https?://.*")) {
            return "ERROR: url 必须是 http/https 地址,当前收到: " + Texts.abbreviate(url, 120);
        }
        try {
            byte[] body = downloadBounded(url, AgentWorkspaceService.MAX_BINARY_BYTES);
            if (body == null || body.length == 0) {
                return "ERROR: 下载到空内容";
            }
            String name = a.path("filename").asText(null);
            if (name == null || name.isBlank()) {
                name = inferFilename(url);
            }
            return fileToolClient.upload(name, body);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: 下载失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200);
        }
    }

    /**
     * 带大小上限的流式下载:先看 Content-Length 快速拒绝,再边读边计数,
     * 超限立即中断(不再全量进内存后才判断——大视频会把堆打爆)。
     *
     * @param url      下载地址
     * @param maxBytes 允许的最大字节数
     * @return 文件字节
     * @throws IllegalArgumentException 超限或下载失败(消息面向用户可读)
     */
    private byte[] downloadBounded(String url, long maxBytes) throws Exception {
        java.net.http.HttpClient.Builder cb = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);
        java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder.addressFor(url);
        if (proxyAddr != null) {
            cb.proxy(java.net.ProxySelector.of(proxyAddr));
        }
        java.net.http.HttpResponse<java.io.InputStream> resp = cb.build().send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "Nora-Agent/1.0")
                        .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalArgumentException("下载失败 HTTP " + resp.statusCode() + "(" + Texts.abbreviate(url, 100) + ")");
        }
        long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            throw new IllegalArgumentException("文件超过 " + (maxBytes / 1024 / 1024) + "MB 上限(源声明 "
                    + (declared / 1024 / 1024) + "MB)");
        }
        try (java.io.InputStream in = resp.body();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("文件超过 " + (maxBytes / 1024 / 1024) + "MB 上限,已中断下载");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 从 URL 下载内容并写入文件系统(manage_workspace action=import)。
     *
     * <p>典型用法:把 MCP 工具(如手机相册)返回的图片链接存到工作区,
     * 供用户在工作台「文件」里查看/整理/归档。下载走全局出站代理配置
     * (外网目标经代理、内网/本机直连,见 ProxySupport)。
     *
     * @param a    工具参数(url / filename 可选)
     * @param path 目标路径(缺省时用下载文件名放到工作区根)
     */
    private String importFromUrl(JsonNode a, String path) {
        String url = a.path("url").asText(null);
        if (url == null || url.isBlank()) {
            return "ERROR: 缺少 url 参数(要下载的地址,如 MCP 工具返回的 contentUrl/thumbUrl)";
        }
        if (!url.matches("(?i)^https?://.*")) {
            return "ERROR: url 必须是 http/https 地址,当前收到: " + Texts.abbreviate(url, 120);
        }
        if (agentWorkspaceService == null) {
            return "ERROR: 工作区能力未启用(服务未配置)";
        }
        // 目标路径:显式给定优先;否则用 filename 或从 URL 推断,放到工作区根
        String target = path;
        if (target == null || target.isBlank()) {
            String name = a.path("filename").asText(null);
            if (name == null || name.isBlank()) {
                name = inferFilename(url);
            }
            target = "imports/" + name;
        }
        try {
            byte[] body = downloadBounded(url, AgentWorkspaceService.MAX_BINARY_BYTES);
            if (body == null || body.length == 0) {
                return "ERROR: 下载到空内容(" + Texts.abbreviate(url, 100) + ")";
            }
            boolean existed = agentWorkspaceService.existsAny(target);
            long written = agentWorkspaceService.writeBinaryAny(target, body);
            return "已保存到 " + target + "(" + FileToolClient.formatSize(written)
                    + (existed ? ",已覆盖同名文件" : "")
                    + ", 来源: " + Texts.abbreviate(url, 90) + ")";
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: 下载失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200);
        }
    }

    /** 从 URL 推断合适的文件名(取路径末段并去掉查询参数)。 */
    private static String inferFilename(String url) {
        try {
            String p = java.net.URI.create(url).getPath();
            if (p != null && p.contains("/")) {
                String last = p.substring(p.lastIndexOf('/') + 1);
                if (!last.isBlank() && last.contains(".")) {
                    return last.replaceAll("[\\\\/:*?\"<>|]", "_");
                }
            }
        } catch (Exception ignored) {
            // 推断失败走默认名
        }
        return "import-" + System.currentTimeMillis() + ".bin";
    }

    /** One executed tool call: bounded content plus UI-facing metadata. */
    record ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                        /** 图片附件(MCP 工具返回的 image 块);空 = 纯文本 */
                        java.util.List<McpServerService.McpToolResult.ImageBlock> images) {

        /** 纯文本结果(旧行为,保持不变) */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated) {
            this(content, summary, rowCount, truncated, java.util.List.of());
        }

        boolean hasImages() {
            return images != null && !images.isEmpty();
        }
    }
}