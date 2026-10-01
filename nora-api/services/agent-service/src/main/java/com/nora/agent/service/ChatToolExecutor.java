package com.nora.agent.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;

/**
 * 工具执行器(从 ChatOrchestrationService 拆出,2026-09-17 复杂度审计 Step 2):
 * 保留工具分发、输出预算及 SQL/服务守卫;文件与 MCP 管理由领域执行器处理。
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
    private final McpServerService mcpServerService;
    private final TerminalService terminalService;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSkillService agentSkillService;
    /** 画廊列表预取(可为 null:测试等场景未接)。 */
    private final GalleryPrefetcher galleryPrefetcher;
    /** 批量媒体拉取(可为 null:测试等场景未接)。 */
    private final MediaFetchService mediaFetchService;
    /** 知识库主动检索与管理(可为 null:测试等场景未接)。 */
    private final KnowledgeManageClient knowledgeManageClient;
    /** 自动任务管理(可为 null:测试等场景未接)。 */
    private final AutomationManageClient automationManageClient;
    /** 环境健康快照(可为 null:测试等场景未接)。 */
    private final EnvironmentStatusClient environmentStatusClient;
    private final FileWorkspaceTools fileTools;
    private final McpManagementTools mcpTools;

    void setViewerService(ViewerService viewerService) { fileTools.setViewerService(viewerService); }

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
        this(objectMapper, sqlToolClient, serviceLogClient, writeSqlClient, containerControlClient,
                dataSourceManageClient, serviceManageClient, fileToolClient, mcpServerService, terminalService,
                agentWorkspaceService, agentSkillService, null, null, null, null, null, null);
    }

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
                     AgentSkillService agentSkillService,
                     GalleryPrefetcher galleryPrefetcher,
                     MediaFetchService mediaFetchService,
                     RelayMediaRouter relayMediaRouter) {
        this(objectMapper, sqlToolClient, serviceLogClient, writeSqlClient, containerControlClient,
                dataSourceManageClient, serviceManageClient, fileToolClient, mcpServerService, terminalService,
                agentWorkspaceService, agentSkillService, galleryPrefetcher, mediaFetchService, relayMediaRouter,
                null, null, null);
    }

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
                     AgentSkillService agentSkillService,
                     GalleryPrefetcher galleryPrefetcher,
                     MediaFetchService mediaFetchService,
                     RelayMediaRouter relayMediaRouter,
                     KnowledgeManageClient knowledgeManageClient,
                     AutomationManageClient automationManageClient,
                     EnvironmentStatusClient environmentStatusClient) {
        this.objectMapper = objectMapper;
        this.sqlToolClient = sqlToolClient;
        this.serviceLogClient = serviceLogClient;
        this.writeSqlClient = writeSqlClient;
        this.containerControlClient = containerControlClient;
        this.dataSourceManageClient = dataSourceManageClient;
        this.serviceManageClient = serviceManageClient;
        this.mcpServerService = mcpServerService;
        this.terminalService = terminalService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.galleryPrefetcher = galleryPrefetcher;
        this.mediaFetchService = mediaFetchService;
        this.fileTools = new FileWorkspaceTools(objectMapper, fileToolClient, agentWorkspaceService, relayMediaRouter);
        this.mcpTools = new McpManagementTools(objectMapper, mcpServerService, galleryPrefetcher);
        this.knowledgeManageClient = knowledgeManageClient;
        this.automationManageClient = automationManageClient;
        this.environmentStatusClient = environmentStatusClient;
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
     * 工具输出预算遵循 harness 调研:成功保留大的内联预算,失败给头+尾摘录
     * (失败只看两端即可诊断,不需要持久化指针)。
     */
    static final int MAX_SUCCESS_CHARS = 30_000;
    static final int MAX_FAILURE_CHARS = 10_000;
    /** 失败摘录保留的头/尾字符数。 */
    private static final int FAILURE_HEAD_CHARS = 6_000;
    private static final int FAILURE_TAIL_CHARS = 3_000;

    /**
     * 工具执行期间的实时输出(双通道):
     * <ul>
     *   <li>{@link #text} —— 文本流(run_command 的实时 stdout);</li>
     *   <li>{@link #progress} —— 结构化进度(fetch_media 的批量下载:
     *       完成数/字节/速率/ETA),前端渲染进度条,不再只给一行文本。</li>
     * </ul>
     * 两个通道都由 ToolStepEmitter 接到「同 id 步骤原地刷新」上——
     * 前端按 id 合并,进度更新即视觉上的原地刷新。
     */
    interface LiveOutput {
        void text(String chunk);

        default void progress(ChatStepDto.StepProgress progress) {
        }

        /**
         * 用户是否已请求停止本会话轮次(轮询式取消信号)。
         * 默认实现查线程中断标志;ToolStepEmitter 的实现额外查
         * {@link TurnCancellation}(不可被下游消费的标志,2026-09-17 修复
         * 「停止生成杀不掉批量下载」)。
         */
        default boolean cancelled() {
            return Thread.currentThread().isInterrupted();
        }
    }

    /**
     * 分发一次工具调用。守卫拒绝返回三段式错误
     * (拒绝了什么 + 哪条规则 + 正确示例),让模型下一轮自我纠正。
     *
     * <p>2026-09-17:各工具分支拆为独立 handler(见下方 exec* 方法),此处只做分发。
     */
    ToolOutcome executeTool(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                                    LiveOutput liveOutput) {
        return executeTool(name, args, parsed, liveOutput, null, null);
    }

    ToolOutcome executeTool(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput, String sessionId, String stepId) {
        if ("open_file".equals(name)) return fileTools.execOpenFile(args, sessionId, stepId);
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
        if ("manage_file".equals(name) || "read_file".equals(name)) {
            // read_file = 旧名兼容别名(2026-09-20 更名 manage_file;旧历史/旧调用仍路由)
            return fileTools.execReadFile(name, args, parsed, liveOutput);
        }
        if ("manage_workspace".equals(name)) {
            return fileTools.execManageWorkspace(name, args, parsed, liveOutput);
        }
        if ("manage_skill".equals(name)) {
            return execManageSkill(name, args, parsed, liveOutput);
        }
        if ("manage_mcp".equals(name)) {
            return mcpTools.execManageMcp(name, args, parsed, liveOutput);
        }
        if ("run_command".equals(name)) {
            return execRunCommand(name, args, parsed, liveOutput);
        }
        if ("fetch_media".equals(name)) {
            return execFetchMedia(name, args, parsed, liveOutput);
        }
        if ("search_knowledge".equals(name)) {
            return execSearchKnowledge(name, args, parsed, liveOutput);
        }
        if ("manage_knowledge".equals(name)) {
            return execManageKnowledge(name, args, parsed, liveOutput);
        }
        if ("manage_automation".equals(name)) {
            return execManageAutomation(name, args, parsed, liveOutput);
        }
        if ("environment_status".equals(name)) {
            return execEnvironmentStatus(name, args, parsed, liveOutput);
        }
        // MCP 挂载工具兜底分发:名字带 mcp__ 前缀 → 路由到对应服务器执行;
        // 输出同样走 bounded 截断与脱敏
        if (mcpServerService != null && name.startsWith("mcp__")) {
            McpServerService.RawServer server = mcpServerService.serverForMountedTool(name);
            if (server == null) {
                // 区分两种失败:格式对但服务器不存在/已停用,还是名字本身就是幻觉
                // (如 mcp__phone_photos_review 少一个下划线)——给可操作的出路。
                boolean malformed = name.indexOf("__", "mcp__".length()) < 0;
                String hint = malformed
                        ? "工具名格式应为 mcp__<服务器名>__<工具名>(服务器名与工具名之间是双下划线);"
                        : "该服务器可能已被禁用或删除;";
                return new ToolOutcome("ERROR: 找不到工具 " + name + " —— " + hint
                        + "先用 manage_mcp action=list 查看可用服务器,再 refresh 拉取工具清单"
                        + "(挂载名以清单为准,不要凭记忆拼写)", null, null, false);
            }
            McpServerService.McpToolResult mcpResult =
                    mcpServerService.callToolRich(server.id(), McpServerService.rawToolName(name), args);
            // 画廊列表预取(2026-09-17):结果里出现 nora-gallery 围栏(photos_showcase)
            // 时立即后台缓存每条媒体的播放流——用户还在看列表的窗口里把手机转码
            // (视频首次 10-15s)与传输都做完,点开即秒播。用未截断全文解析。
            if (galleryPrefetcher != null && !mcpResult.isError()) {
                galleryPrefetcher.prefetchFromToolResult(mcpResult.text());
            }
            ToolOutcome mcpOutcome = bounded(mcpResult.text(), "MCP " + server.name() + " 执行完成");
            // 结果未知(调用已发出但中断,远端可能已生效;设计 §5.2):单独状态,
            // 不混入普通 failed——前端橙色警示、运行终态记 partial
            if (mcpResult.unknown()) {
                mcpOutcome = new ToolOutcome(mcpOutcome.content(), mcpOutcome.summary(),
                        mcpOutcome.rowCount(), mcpOutcome.truncated(), mcpOutcome.images(), true);
            }
            // 保留 image 块:图片本体不参与文本截断(避免把 base64 当文本切)，
            // 由回填层按模型识图能力决定是否附上
            return mcpResult.images().isEmpty() ? mcpOutcome
                    : new ToolOutcome(mcpOutcome.content(), mcpOutcome.summary(), mcpOutcome.rowCount(),
                            mcpOutcome.truncated(), mcpResult.images(), mcpOutcome.unknown());
        }
        return new ToolOutcome("ERROR: unknown tool " + name
                + ". 可用工具：execute_sql（只读 SQL,可选 datasource 参数）、execute_write_sql（写 SQL,需批准）、"
                + "read_service_logs（容器日志）、environment_status（环境健康快照）、"
                + "manage_container（容器启停,需批准）、"
                + "manage_datasource（数据源 list/create/test/schema/remove,create/remove 需批准）、"
                + "manage_service（纳管源 list/register/enable/disable/remove,register/remove 需批准）、"
                + "manage_file（文件中心:用户上传文件 list/read/import/rename/move/delete/folders/mkdir）、"
                + "manage_workspace（工作区文件 list/read/write/append/delete/edit/import/move/copy/mkdir）、"
                + "search_knowledge（知识库主动检索）、manage_knowledge（知识库 list/index/remove/reindex/stats）、"
                + "manage_automation（自动任务 list/create/toggle/remove/run/executions）、"
                + "manage_skill（技能 list/read/create/update/remove）、"
                + "manage_mcp（MCP 服务器 list/refresh/enable/disable/register/update/remove,风险跟随权限档位）、"
                + "ask_user（向用户提问并等待回答——指代不明/缺关键信息时用,而不是猜测）、"
                + "run_command（本机终端非交互命令,风险跟随权限档位）"
                + (name.startsWith("mcp__") ? " 或已挂载的 MCP 工具(mcp__<server>__<tool>)" : ""), null, null, false);
    }

    /** execute_sql handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execExecuteSql(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        String sql = parsed.input().sql() != null ? parsed.input().sql() : "";
        // guard 按目标引擎分派(2026-09-29 修复):Redis 连接的命令形态是
        // INFO/KEYS/GET 等,SQL 语法 guard 会误拦;redis 走命令白名单语义
        // (与 datasource-service 的 RedisGuard 同一份清单口径,见其 ALLOWED)。
        SqlToolClient.Target target = sqlToolClient.resolveTarget(parsed.input().target());
        String engine = target == null ? "" : target.engine();
        String guard = "redis".equals(engine) ? guardRedisCommand(sql) : guardSql(sql);
        if (guard != null) {
            return new ToolOutcome("ERROR: " + guard, null, null, false);
        }
        SqlToolClient.SqlOutcome outcome = sqlToolClient.executeSqlDetailed(sql, target);
        return new ToolOutcome(outcome.content(), outcome.summary(), null, outcome.truncated());
    }

    /** read_service_logs handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execReadServiceLogs(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
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
                            LiveOutput liveOutput) {
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
                            LiveOutput liveOutput) {
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
                            LiveOutput liveOutput) {
        // 与分类器同一归一化:add→create / delete→remove 等别名(复盘数据驱动,
        // 实测模型写 add/delete 被拒);schema 在工具 spec 里不宣传但白名单放行
        // (只读,模型从 list 结果推断时可用)
        String action = RiskClassifier.normalizeDatasourceAction(parsed.datasourceAction());
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
                            LiveOutput liveOutput) {
        // 与分类器同一归一化(add→register / delete→remove / stop→disable 等)
        String action = RiskClassifier.normalizeServiceAction(parsed.datasourceAction());
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

    /** manage_skill handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageSkill(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
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
                    // 记录实际加载的内容版本(设计 §7):便于复盘「这次用的是哪一版技能」
                    // (技能可在会话中途被 update——版本时间戳让行为变化可追溯)
                    yield "技能「" + skill.name() + "」完整指令"
                            + (skill.updatedAt() != null ? "(版本:" + skill.updatedAt() + ")" : "")
                            + ":\n" + skill.instructions();
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

    /** run_command handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execRunCommand(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (terminalService == null) {
            return new ToolOutcome("ERROR: 终端能力未启用(服务未配置)", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            String cmd = a.path("command").asText(null);
            String cwdArg = a.path("cwd").asText(null);
            Integer timeout = a.path("timeout").isInt() ? a.path("timeout").asInt() : null;
            String shell = a.path("shell").asText(null);
            TerminalService.RunResult r = terminalService.run(cmd, cwdArg, timeout, shell,
                    liveOutput == null ? null : liveOutput::text);
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
     * fetch_media handler(2026-09-17):批量媒体拉取——从手机相册 MCP 拿清单,
     * 并发下载到工作区文件夹,一次调用完成「整理相册」类任务。
     *
     * <p>设计动机(实测事故):此前 agent 只能对每条 URL 跑 run_command 逐个下载
     * (407 个文件 = 400+ 轮工具调用),且因猜 API 路径触发远端 fail2ban 封 IP
     * 导致整轮失败。批量拉取必须是一等工具,让 agent 不必自己拼命令。
     */
    ToolOutcome execFetchMedia(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (mediaFetchService == null) {
            return new ToolOutcome("ERROR: 媒体拉取能力未启用(服务未配置)", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            String serverName = a.path("server").asText("phone");
            String from = a.path("from").asText(null);
            String to = a.path("to").asText(null);
            String album = a.path("album").asText(null);
            String type = a.path("type").asText(null);
            String folder = a.path("folder").asText(null);
            String quality = a.path("quality").asText(null);
            long start = System.currentTimeMillis();
            // 进度回调只发结构化 progress 通道(同 id step 原地刷新:完成数/字节/
            // 速率/ETA → 前端进度卡片)。**不要再调 text()**:那会发第二条同 id 事件
            // 把 progress 字段覆盖掉——前端只拿到文本行,进度卡永远不出现(实测 bug)。
            // detail 的文本行由 ToolStepEmitter 的 progress 分支统一渲染。
            MediaFetchService.ProgressCallback progress = liveOutput == null ? null
                    : fp -> liveOutput.progress(toStepProgress(fp));
            MediaFetchService.FetchReport report = mediaFetchService.fetchFromPhone(
                    serverName, from, to, album, type, folder, quality, progress,
                    liveOutput == null ? null : liveOutput::cancelled);
            long elapsed = System.currentTimeMillis() - start;
            if (report.cancelled()) {
                // 用户「停止生成」:下载器已停,如实回报(模型下一轮会被编排层的
                // 中断检查拦住,不会继续;此消息仅落库留痕)
                return new ToolOutcome("已取消:用户停止了本次生成。已下载 "
                        + report.downloaded() + " 个(共 " + report.total() + " 个),未完成部分可稍后重跑续传。",
                        "用户已取消", report.downloaded(), false);
            }
            if (report.total() == 0 && report.failed() == 0 && report.errors().isEmpty()) {
                // 手机端有可操作提示(如「相册不存在,可用相册:…」)时原样转给模型,
                // 它才能自纠参数重试;没有提示才是真的范围内无媒体。
                if (report.hint() != null && !report.hint().isBlank()) {
                    return new ToolOutcome("没有拉到媒体。" + report.hint()
                            + "\n(修正参数后重试;album 不传=全部相册)", "0 条媒体(参数有误)", 0, false);
                }
                return new ToolOutcome("(范围内没有可导出的媒体——确认时间范围/相册名是否正确;"
                        + "可用 albums_list 查看相册名,photos_stats 看总数)", "0 条媒体", 0, false);
            }
            if (report.total() == 0 && report.failed() > 0) {
                // 清单阶段失败(如手机离线):没有开始任何下载——输出必须是错误,
                // 而不是「已拉取到 …:成功 0,失败 1,共 0」这种自相矛盾的"成功"文案
                // (2026-09-19 修复:模型看到"已拉取到"会误以为任务完成)
                String detail = report.errors().isEmpty() ? "" : "(" + report.errors().get(0) + ")";
                return new ToolOutcome("ERROR: 拉取媒体清单失败,未开始下载 " + detail
                        + "\n下一步:确认手机 App 在线(MCP 服务器状态),或在 MCP 页刷新连接后重试。",
                        "清单失败", 0, false);
            }
            StringBuilder sb = new StringBuilder();
            // 落地位置必须给**解析后的真实路径**(相对工作区 + 绝对):
            // 只回显 folder 参数时模型无法确认文件到底在哪,只能再跑 PowerShell
            // 验证(实测:一轮任务里 4 个 run_command 全是为确认落点)。
            String folderLabel = (folder == null || folder.isBlank()) ? "imports" : folder.trim();
            sb.append("已拉取到 ").append(folderLabel);
            if (agentWorkspaceService != null) {
                try {
                    AgentWorkspaceService.ResolvedTarget resolved = agentWorkspaceService.resolveAny(folderLabel);
                    sb.append("(绝对路径: ").append(resolved.path().toString().replace('\\', '/')).append(')');
                } catch (Exception ignored) {
                    // 路径解析失败不影响结果报告
                }
            }
            sb.append(":成功 ").append(report.downloaded())
                    .append(",跳过(已存在)").append(report.skipped())
                    .append(",失败 ").append(report.failed())
                    .append(",共 ").append(report.total())
                    .append(",耗时 ").append(elapsed / 1000).append("s\n");
            // 跨目录重复提示(2026-09-18 复查):photos/ 下已出现 11G 冗余
            // (full-album 是全量超集,month-all 等互相重叠)。列出同级目录的
            // 文件数,让模型自己发现"这批素材可能已在别的目录"——把去重判断
            // 交给能看到全局的模型,而不是猜。
            if (agentWorkspaceService != null && folderLabel.startsWith("photos")) {
                try {
                    AgentWorkspaceService.ResolvedTarget photosRoot = agentWorkspaceService.resolveAny("photos");
                    List<AgentWorkspaceService.FileEntry> siblings =
                            agentWorkspaceService.listAny(photosRoot.path().toString());
                    StringBuilder dirs = new StringBuilder();
                    for (AgentWorkspaceService.FileEntry f : siblings) {
                        if (f.directory()) {
                            // f.path() 已是相对工作区根的完整路径(photos/xxx)——直接展示,
                            // 不要再拼 "photos/" 前缀(实测踩过:显示成 photos/photos/xxx)
                            dirs.append("- ").append(f.path()).append("(")
                                    .append(f.fileCount()).append(" 个文件, ")
                                    .append(FileToolClient.formatSize(f.size())).append(")\n");
                        }
                    }
                    if (!dirs.isEmpty()) {
                        sb.append("photos/ 下已有目录(归档前可先比对,避免重复下载):\n").append(dirs);
                    }
                } catch (Exception ignored) {
                    // 概览失败不影响结果报告
                }
            }
            if (!report.errors().isEmpty()) {
                sb.append("失败明细(前 ").append(Math.min(10, report.errors().size())).append(" 条):\n");
                for (int i = 0; i < Math.min(10, report.errors().size()); i++) {
                    sb.append("- ").append(report.errors().get(i)).append('\n');
                }
            }
            // 状态从统计派生(2026-09-20 验收 F3):「可用结果」= 本次下载成功 +
            // 已存在跳过(跳过也是目录里的可用文件)。全部失败=失败(不是"部分
            // 成功");有可用结果且还有失败项才是部分成功;状态与文案同源。
            int usable = report.downloaded() + report.skipped();
            boolean allFailed = report.failed() > 0 && usable == 0;
            boolean someFailed = report.failed() > 0 && usable > 0;
            if (allFailed) {
                String detail = report.errors().isEmpty() ? "" : "(" + report.errors().get(0) + ")";
                return new ToolOutcome("ERROR: 全部 " + report.failed() + " 个文件下载失败,未得到可用结果 " + detail
                        + "\n下一步:确认手机 App 在线与网络状态,或缩小范围重试;已存在的文件会跳过,可安全重跑。",
                        "全部失败(" + report.failed() + "/" + report.total() + ")", 0, false);
            }
            if (someFailed) {
                // 部分成功:模型据此只处理未完成项,而不是声称"全部完成"
                sb.append("\n(部分成功:").append(report.failed())
                        .append(" 个文件失败——可只重试失败项,已成功的会跳过)");
            }
            ToolOutcome fetchOutcome = bounded(sb.toString(), "下载 " + report.downloaded() + "/" + report.total()
                    + (report.failed() > 0 ? ",失败 " + report.failed() : ""));
            return someFailed
                    ? new ToolOutcome(fetchOutcome.content(), fetchOutcome.summary(), fetchOutcome.rowCount(),
                            fetchOutcome.truncated(), java.util.List.of(), false, true)
                    : fetchOutcome;
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + Texts.abbreviate(e.getMessage(), 300), null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 媒体拉取失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** search_knowledge handler(2026-09-18 知识库工具面补齐)。 */
    ToolOutcome execSearchKnowledge(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (knowledgeManageClient == null) {
            return new ToolOutcome("ERROR: 知识库检索能力未启用(服务未配置)", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            String query = a.path("query").asText(null);
            if (query == null || query.isBlank()) {
                return new ToolOutcome("ERROR: 缺少 query 参数(检索关键词或自然语言查询)。"
                        + "示例:{\"query\": \"部署流程 端口\", \"topK\": 8}", null, null, false);
            }
            int topK = a.path("topK").isInt() ? a.path("topK").asInt() : 8;
            // 范围(阶段 B):baseId=资料库;docIds=指定文档(「只在这批资料里找」)
            Long baseId = a.hasNonNull("baseId") && a.path("baseId").canConvertToLong()
                    ? a.path("baseId").asLong() : null;
            java.util.List<Long> docIds = null;
            if (a.hasNonNull("docIds") && a.path("docIds").isArray() && !a.path("docIds").isEmpty()) {
                docIds = new java.util.ArrayList<>();
                for (JsonNode n : a.path("docIds")) {
                    if (n.canConvertToLong()) {
                        docIds.add(n.asLong());
                    }
                }
            }
            return bounded(knowledgeManageClient.search(query, topK, baseId, docIds), null);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 知识库检索失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** manage_knowledge handler(2026-09-18 知识库工具面补齐)。 */
    ToolOutcome execManageKnowledge(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (knowledgeManageClient == null) {
            return new ToolOutcome("ERROR: 知识库管理能力未启用(服务未配置)", null, null, false);
        }
        String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
        if (!java.util.Set.of("list", "bases", "index", "remove", "reindex", "disable", "enable", "stats").contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 "
                    + "list / bases / index / remove / reindex / disable / enable / stats",
                    null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            return switch (action) {
                case "list" -> bounded(knowledgeManageClient.list(a.path("filter").asText(null)), null);
                case "bases" -> bounded(knowledgeManageClient.listBases(), null);
                case "stats" -> bounded(knowledgeManageClient.stats(), null);
                case "index" -> {
                    String fileIdRaw = a.path("fileId").asText(null);
                    if (fileIdRaw == null || !fileIdRaw.matches("\\d+")) {
                        yield new ToolOutcome("ERROR: index 需要 fileId 参数(文件中心文件的数字 id;先 manage_file action=list 查看)。"
                                + "示例:{\"action\": \"index\", \"fileId\": \"12\"}", null, null, false);
                    }
                    yield bounded(knowledgeManageClient.indexFile(Long.parseLong(fileIdRaw),
                            a.path("name").asText(null)), null);
                }
                default -> {
                    String target = a.path("target").asText(null);
                    if (target == null || !target.matches("\\d+")) {
                        yield new ToolOutcome("ERROR: " + action + " 需要 target 参数(知识库文档的数字 id;先 action=list 查看)",
                                null, null, false);
                    }
                    long docId = Long.parseLong(target);
                    yield bounded(switch (action) {
                        case "remove" -> knowledgeManageClient.remove(docId);
                        case "disable" -> knowledgeManageClient.setEnabled(docId, false);
                        case "enable" -> knowledgeManageClient.setEnabled(docId, true);
                        default -> knowledgeManageClient.reindex(docId);
                    }, null);
                }
            };
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 知识库操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** manage_automation handler(2026-09-18 自动任务工具面补齐)。 */
    ToolOutcome execManageAutomation(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (automationManageClient == null) {
            return new ToolOutcome("ERROR: 自动任务能力未启用(服务未配置)", null, null, false);
        }
        String action = parsed.datasourceAction() == null ? "" : parsed.datasourceAction().trim().toLowerCase();
        if (!java.util.Set.of("list", "create", "toggle", "remove", "run", "executions").contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 "
                    + "list / create / toggle / remove / run / executions", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            return switch (action) {
                case "list" -> bounded(automationManageClient.list(), null);
                case "executions" -> {
                    int limit = a.path("limit").isInt() ? a.path("limit").asInt() : 20;
                    yield bounded(automationManageClient.executions(limit), null);
                }
                case "create" -> {
                    String rName = a.path("name").asText(null);
                    String prompt = a.path("prompt").asText(null);
                    String trigger = a.path("triggerType").asText("daily");
                    if (rName == null || rName.isBlank()) {
                        yield new ToolOutcome("ERROR: create 需要 name 参数(规则名,如「每日订单巡检」)", null, null, false);
                    }
                    if (prompt == null || prompt.isBlank()) {
                        yield new ToolOutcome("ERROR: create 需要 prompt 参数(未来无人值守轮次执行的自然语言指令;"
                                + "写清楚要做什么、输出什么——执行时没有人在场追问)。"
                                + "示例:{\"action\": \"create\", \"name\": \"每日订单巡检\", \"triggerType\": \"daily\","
                                + " \"prompt\": \"查询今天的订单总量与异常订单,输出简短摘要\"}", null, null, false);
                    }
                    if (!java.util.Set.of("daily", "weekly", "manual").contains(trigger)) {
                        yield new ToolOutcome("ERROR: triggerType 只允许 daily / weekly / manual,收到: " + trigger,
                                null, null, false);
                    }
                    // 日程(daily/weekly 必填;2026-09-21 修复):后端 M4 起强制校验,
                    // 此前 agent 不传 schedule 导致 daily/weekly 创建必然失败(实测 400)
                    String scheduleJson = null;
                    if ("daily".equals(trigger) || "weekly".equals(trigger)) {
                        String localTime = a.path("localTime").asText("").trim();
                        if (!localTime.matches("\\d{1,2}:\\d{2}")) {
                            yield new ToolOutcome("ERROR: triggerType=" + trigger + " 需要 localTime 参数(HH:mm,如 09:00)"
                                    + "。示例:{\"action\": \"create\", \"name\": \"每日巡检\", \"triggerType\": \"daily\","
                                    + " \"localTime\": \"09:00\", \"prompt\": \"...\"}", null, null, false);
                        }
                        java.util.Map<String, Object> sched = new java.util.LinkedHashMap<>();
                        sched.put("frequency", trigger);
                        sched.put("localTime", localTime);
                        if ("weekly".equals(trigger)) {
                            int dow = a.path("dayOfWeek").asInt(0);
                            if (dow < 1 || dow > 7) {
                                yield new ToolOutcome("ERROR: triggerType=weekly 需要 dayOfWeek 参数(1=周一 … 7=周日),收到: "
                                        + a.path("dayOfWeek").asText("(缺)"), null, null, false);
                            }
                            sched.put("dayOfWeek", dow);
                        }
                        String tz = a.path("timezone").asText("").trim();
                        if (!tz.isBlank()) {
                            sched.put("timezone", tz);
                        }
                        try {
                            scheduleJson = objectMapper.writeValueAsString(sched);
                        } catch (Exception e) {
                            yield new ToolOutcome("ERROR: 日程参数序列化失败: " + e.getMessage(), null, null, false);
                        }
                    }
                    yield bounded(automationManageClient.create(rName, trigger, prompt, scheduleJson), null);
                }
                default -> {
                    String target = a.path("target").asText(null);
                    if (target == null || !target.matches("\\d+")) {
                        yield new ToolOutcome("ERROR: " + action + " 需要 target 参数(规则的数字 id;先 action=list 查看)",
                                null, null, false);
                    }
                    long ruleId = Long.parseLong(target);
                    yield bounded(switch (action) {
                        case "toggle" -> automationManageClient.toggle(ruleId);
                        case "remove" -> automationManageClient.remove(ruleId);
                        default -> automationManageClient.run(ruleId);
                    }, null);
                }
            };
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 自动任务操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** environment_status handler(2026-09-18 诊断入口)。 */
    ToolOutcome execEnvironmentStatus(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (environmentStatusClient == null) {
            return new ToolOutcome("ERROR: 环境状态能力未启用(服务未配置)", null, null, false);
        }
        return bounded(environmentStatusClient.statusSummary(), null);
    }

    /** MediaFetch 进度 → 步骤事件的结构化 progress(前端进度卡片直接消费)。 */
    private static ChatStepDto.StepProgress toStepProgress(MediaFetchService.FetchProgress fp) {
        return new ChatStepDto.StepProgress(fp.phase(), fp.done(), fp.total(), fp.currentIndex(),
                fp.currentFile(), fp.active(), fp.bytesDone(), fp.bytesTotal(), fp.bytesPerSec(),
                fp.etaSeconds());
    }

    /** 进度的人类可读一行(步骤 detail;也是旧前端的降级展示)。 */
    private static String renderProgressLine(MediaFetchService.FetchProgress fp) {
        if ("listing".equals(fp.phase())) {
            return "正在获取清单…已取到 " + fp.done() + " 条";
        }
        StringBuilder sb = new StringBuilder("下载中 ").append(fp.done()).append('/').append(fp.total());
        if (fp.active() > 1) {
            sb.append("(并行 ").append(fp.active()).append(')');
        }
        sb.append(" · ").append(FileToolClient.formatSize(fp.bytesDone()));
        if (fp.bytesTotal() > 0) {
            sb.append('/').append(FileToolClient.formatSize(fp.bytesTotal()));
        }
        if (fp.bytesPerSec() != null && fp.bytesPerSec() > 0) {
            sb.append(" · ").append(FileToolClient.formatSize(fp.bytesPerSec())).append("/s");
        }
        if (fp.etaSeconds() != null) {
            sb.append(" · 约剩 ").append(formatEta(fp.etaSeconds()));
        }
        if (fp.currentFile() != null) {
            sb.append(" · ").append(Texts.abbreviate(fp.currentFile(), 40));
        }
        return sb.toString();
    }

    /** ETA 人类可读:90s → "1分30秒";3600s → "1小时"。 */
    static String formatEta(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分" + (seconds % 60 > 0 ? (seconds % 60) + " 秒" : "");
        }
        return (seconds / 3600) + " 小时" + (seconds % 3600 / 60 > 0 ? (seconds % 3600 / 60) + " 分" : "");
    }

    /**
     * 守卫:只允许单条只读语句。拒绝时携带被拒内容、规则与正确示例。
     */
    static String guardSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "拒绝执行：缺少 sql 参数。该工具需要一条只读 SQL 语句，正确示例：{\"sql\": \"SELECT * FROM orders LIMIT 10\"}";
        }
        String normalized = sql.trim();
        if (!normalized.matches("(?is)^(SELECT|SHOW|EXPLAIN)\\b.*")) {
            return "拒绝执行「" + Texts.abbreviate(normalized, 60) + "」：只允许单条 SELECT / SHOW / EXPLAIN 查询，"
                    + "不允许写入或修改数据。正确示例：{\"sql\": \"SELECT status, COUNT(*) FROM orders GROUP BY status\"}"
                    + "(目标若是 Redis 连接,请改用 Redis 只读命令,如 GET/KEYS/SCAN/INFO)";
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

    /**
     * Redis 连接的只读命令守卫(2026-09-29):与 datasource-service 的
     * {@link com.nora.datasource.service.RedisGuard} 同一份白名单口径——
     * agent 侧先拦一次给可自纠的提示,最终防线仍在 datasource-service。
     * 只读命令清单保持与 RedisGuard.ALLOWED 同步(键空间/字符串/哈希/
     * 列表/集合/有序集合/流/位图/服务信息)。
     */
    static String guardRedisCommand(String command) {
        if (command == null || command.isBlank()) {
            return "拒绝执行：缺少 sql 参数(Redis 连接请传只读命令,如 INFO server / KEYS nora* / GET key)";
        }
        String trimmed = command.trim();
        if (trimmed.contains(";")) {
            return "拒绝执行：一次只允许一条命令(检测到分号)。请拆成多次调用";
        }
        String[] parts = trimmed.split("\\s+");
        String verb = parts[0].toLowerCase(java.util.Locale.ROOT);
        if (!REDIS_READONLY_VERBS.contains(verb)) {
            return "拒绝执行「" + Texts.abbreviate(trimmed, 40) + "」：Redis 连接只允许只读命令"
                    + "(GET/MGET/KEYS/SCAN/TYPE/TTL/EXISTS/DBSIZE/HGETALL/LRANGE/SMEMBERS/ZRANGE/XINFO/INFO/TIME 等)。"
                    + "写命令(SET/DEL/FLUSHALL 等)在任何通道都不被允许";
        }
        if (parts.length > 8) {
            return "拒绝执行：命令参数过多(最多 8 个)";
        }
        return null;
    }

    /** Redis 只读命令白名单(与 datasource-service RedisGuard.ALLOWED 同步)。 */
    private static final java.util.Set<String> REDIS_READONLY_VERBS = java.util.Set.of(
            // 键空间 / 元数据
            "keys", "scan", "type", "ttl", "pttl", "exists", "dbsize", "randomkey", "object",
            // 字符串
            "get", "mget", "strlen", "getrange", "substr",
            // 哈希
            "hget", "hmget", "hgetall", "hkeys", "hvals", "hlen", "hexists", "hscan", "hrandfield",
            // 列表
            "lrange", "llen", "lindex", "lpos",
            // 集合
            "smembers", "scard", "sismember", "srandmember", "sscan", "smismember",
            // 有序集合
            "zrange", "zrevrange", "zrangebyscore", "zrevrangebyscore", "zrangebylex", "zcard",
            "zscore", "zmscore", "zrank", "zrevrank", "zcount", "zscan", "zrandmember",
            // 流 / 位图 / HyperLogLog(只读形式)
            "xrange", "xrevrange", "xlen", "xinfo", "getbit", "bitcount", "bitpos", "pfcount",
            // 服务信息
            "info", "time", "memory", "command", "lastsave", "lolwut");

    /** 服务日志工具的守卫:服务名必须来自注册表。 */
    static String guardService(String service) {
        if (service == null || service.isBlank()) {
            return "拒绝执行：缺少 service 参数。该工具需要容器名，可用值如：nora-postgres、nora-redis、nora-nacos";
        }
        return null;
    }

    /**
     * 把原始工具结果限制到内联预算。成功最多保留
     * {@value MAX_SUCCESS_CHARS} 字符,超出以截断标记收尾;
     * 失败(ERROR:)则给头+尾摘录。
     */
    static ToolOutcome bounded(String result, String summary) {
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

    /** 从 TSV 渲染推导一行行数摘要("(3 rows, 12ms)" 页脚)。 */
    private static String summarizeRows(String tsv) {
        int idx = tsv.lastIndexOf("(");
        if (idx >= 0 && tsv.endsWith(")")) {
            return tsv.substring(idx + 1, tsv.length() - 1);
        }
        return null;
    }

    /**
     * 把纳管源名称(或数字 id)解析为 env-service id。名称对整个注册表做
     * 不区分大小写匹配(含已暂停的源——/services 会省略它们)。
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

    /** 一次执行的工具调用:有界内容 + 面向 UI 的元数据。 */
    record ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                        /** 图片附件(MCP 工具返回的 image 块);空 = 纯文本 */
                        java.util.List<McpServerService.McpToolResult.ImageBlock> images,
                        /** 结果未知(调用已发出但中断,远端可能已生效;设计 §5.2):
                         *  与普通失败区分——前端橙色警示、运行终态记 partial。 */
                        boolean unknown,
                        /** 部分成功(批量任务有成功也有失败;设计 §5.2):
                         *  保留成功/失败/跳过区别,不显示为全量成功。 */
                        boolean partial,
                        java.util.List<com.nora.agent.dto.ViewerFile> files,
                        String focusTarget,
                        java.util.List<ViewerService.FileError> fileErrors,
                        /** 文本写操作的变更记录(对话「已编辑 N 个文件」卡片,2026-09-30):
                         *  仅工作区普通内容文件;内部状态文件/区外文件不记录。空 = 非写操作。 */
                        java.util.List<ChatStepDto.FileChange> fileChanges) {

        /** 带文件清单的结果(兼容构造:无 fileChanges)。 */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown, boolean partial,
                    java.util.List<com.nora.agent.dto.ViewerFile> files, String focusTarget,
                    java.util.List<ViewerService.FileError> fileErrors) {
            this(content, summary, rowCount, truncated, images, unknown, partial, files, focusTarget, fileErrors, null);
        }

        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown, boolean partial) {
            this(content, summary, rowCount, truncated, images, unknown, partial, null, null, null);
        }

        /** 纯文本结果(旧行为,保持不变) */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated) {
            this(content, summary, rowCount, truncated, java.util.List.of(), false, false);
        }

        /** 带图片的结果(兼容构造:非 unknown/partial)。 */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images) {
            this(content, summary, rowCount, truncated, images, false, false);
        }

        /** 带 unknown 的结果(兼容构造:非 partial)。 */
        ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                    java.util.List<McpServerService.McpToolResult.ImageBlock> images, boolean unknown) {
            this(content, summary, rowCount, truncated, images, unknown, false);
        }

        boolean hasImages() {
            return images != null && !images.isEmpty();
        }
    }
}
