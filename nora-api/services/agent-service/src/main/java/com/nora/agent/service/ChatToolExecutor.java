package com.nora.agent.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;

/**
 * 工具执行器(从 ChatOrchestrationService 拆出,2026-09-17 复杂度审计 Step 2):
 * executeTool 的 11 路分发 + 结果裁剪(bounded)+ 守卫(guardSql/guardService)
 * + URL 导入(importFromUrl/importToWorkbenchFile/downloadBounded)。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class ChatToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatToolExecutor.class);

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
    /** 画廊列表预取(可为 null:测试等场景未接)。 */
    private final GalleryPrefetcher galleryPrefetcher;
    /** 批量媒体拉取(可为 null:测试等场景未接)。 */
    private final MediaFetchService mediaFetchService;
    /** 链路自动选择(局域网优先;可为 null)。 */
    private final RelayMediaRouter relayMediaRouter;
    /** 知识库主动检索与管理(可为 null:测试等场景未接)。 */
    private final KnowledgeManageClient knowledgeManageClient;
    /** 自动任务管理(可为 null:测试等场景未接)。 */
    private final AutomationManageClient automationManageClient;
    /** 环境健康快照(可为 null:测试等场景未接)。 */
    private final EnvironmentStatusClient environmentStatusClient;

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
        this.fileToolClient = fileToolClient;
        this.mcpServerService = mcpServerService;
        this.terminalService = terminalService;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.galleryPrefetcher = galleryPrefetcher;
        this.mediaFetchService = mediaFetchService;
        this.relayMediaRouter = relayMediaRouter;
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
                + "manage_mcp（MCP 服务器 list/refresh/enable/disable/register/remove,风险跟随权限档位）、"
                + "run_command（本机终端非交互命令,风险跟随权限档位）"
                + (name.startsWith("mcp__") ? " 或已挂载的 MCP 工具(mcp__<server>__<tool>)" : ""), null, null, false);
    }

    /** execute_sql handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execExecuteSql(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
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

    /** read_file handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execReadFile(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        String action = parsed.datasourceAction() == null ? "list" : parsed.datasourceAction().trim().toLowerCase();
        // action 白名单(2026-09-18):非空且未知的 action 报错而不是静默 fallback 到
        // read/list——静默 fallback 会把「模型写错 action」变成「奇怪的成功」,
        // 掩盖问题且误导后续轮次。省略 action 时保留兼容:有 id 即 read,无 id 即 list。
        if (parsed.datasourceAction() != null && !parsed.datasourceAction().isBlank()
                && !java.util.Set.of("list", "read", "import", "rename", "move", "delete", "folders", "mkdir")
                .contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 "
                    + "list / read / import / rename / move / delete / folders / mkdir"
                    + "(文件中心;工作区/整机文件用 manage_workspace)", null, null, false);
        }
        // import: 把远程 URL 下载并存成工作台文件(用户可见可管理)
        if ("import".equals(action)) {
            String r = importToWorkbenchFile(args);
            return r.startsWith("ERROR:") ? new ToolOutcome(r, null, null, false)
                    : new ToolOutcome(r, r, null, false);
        }
        // 文件中心管理面(2026-09-18 复查补齐):rename/move/delete/folders/mkdir——
        // 用户说「把上传的合同改名/移到文件夹/删掉/建个文件夹」时用这些,
        // 不必让用户去 UI 操作
        if ("rename".equals(action) || "move".equals(action) || "delete".equals(action)
                || "folders".equals(action) || "mkdir".equals(action)) {
            return execFileManage(action, args);
        }
        if ("list".equals(action) || parsed.input().target() == null) {
            // 无 id = 列出文件让模型挑;显式 action=list 同理
            return bounded(fileToolClient.list(), null);
        }
        String target = parsed.input().target();
        if (!target.matches("\\d+")) {
            // 路径路由(2026-09-18 文件工具设计分析):模型常把「读文件」的心智
            // 模型合并——对文件中心写工作区路径(id="MEMORY.md")或对工作区文件
            // 用 read_file。这里把「非数字 target」路由到正确的通道,而不是报错:
            //   @center/名 或纯文件名 → 先按名查文件中心,命中即读文件中心
            //   其余(含 / 的路径、工作区文件) → 转 manage_workspace.readAny
            return routeReadByPath(target);
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

    /**
     * read_file 非数字 target 的路径路由(2026-09-18 文件工具设计分析):
     * 模型对「读文件」有单一心智模型,但 Nora 有两个寻址域(文件中心=数字 id,
     * 工作区/整机=路径)。与其报错教学,不如按形态路由到正确通道:
     *
     * <ul>
     *   <li>{@code @center/名} → 明确走文件中心按名查找;</li>
     *   <li>纯文件名(无路径分隔符)→ 先试文件中心按名查(命中即读);未命中
     *       转工作区(模型多半想读 MEMORY.md 这类工作区文件);</li>
     *   <li>含 {@code /} 或绝对路径 → 直接转 manage_workspace.readAny。</li>
     * </ul>
     *
     * 图片文件同样走图像通道(复用 execManageWorkspace 的 read 分支能力)。
     */
    private ToolOutcome routeReadByPath(String target) {
        String t = target.trim();
        boolean explicitCenter = t.startsWith("@center/");
        boolean hasSeparator = t.contains("/") || t.contains("\\");
        // 1) 显式 @center/ 或纯文件名:先试文件中心
        if (explicitCenter || !hasSeparator) {
            Long fileId = fileToolClient.findIdByName(t);
            if (fileId != null) {
                FileToolClient.PreviewInfo info = fileToolClient.previewInfo(fileId);
                if (!info.failed()) {
                    if (info.hasText()) {
                        return bounded("(文件中心 id=" + fileId + ")\n" + fileToolClient.renderPreview(info), null);
                    }
                    // 无文本:复用图像通道逻辑(与数字 id 路径一致)
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
            }
            if (explicitCenter) {
                return new ToolOutcome("ERROR: 文件中心没有名为「" + t + "」的文件(用 action=list 查看全部文件)",
                        null, null, false);
            }
        }
        // 2) 其余(含路径分隔符、或文件中心未命中的纯文件名)→ 工作区/整机读
        if (agentWorkspaceService == null) {
            return new ToolOutcome("ERROR: 文件中心没有「" + t + "」,且工作区能力未启用(服务未配置)",
                    null, null, false);
        }
        try {
            // 复用工作区 read 的完整能力(图片图像通道/文本读取/截断)
            return execManageWorkspaceRead(t);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 读取失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** 工作区 read 的轻量封装(供路径路由复用;不含 manage_workspace 的 action 校验开销)。 */
    private ToolOutcome execManageWorkspaceRead(String path) {
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
        try {
            return bounded("(工作区文件 " + path + ")\n" + agentWorkspaceService.readAny(path), null);
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        }
    }

    /**
     * read_file 管理动作(2026-09-18 复查补齐):rename / move / delete / folders / mkdir。
     * 参数:id(文件 id,可逗号分隔多个) / name(新文件名或文件夹名) / folderId(目标文件夹,null=根)。
     */
    private ToolOutcome execFileManage(String action, String args) {
        JsonNode a;
        try {
            a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 参数不是合法 JSON: " + e.getMessage(), null, null, false);
        }
        switch (action) {
            case "folders" -> {
                return bounded(fileToolClient.folders(), null);
            }
            case "mkdir" -> {
                String folderName = a.path("name").asText(null);
                if (folderName == null || folderName.isBlank()) {
                    return new ToolOutcome("ERROR: mkdir 需要 name 参数(新文件夹名)。"
                            + "示例:{\"action\": \"mkdir\", \"name\": \"合同\"}", null, null, false);
                }
                return bounded(fileToolClient.mkdir(folderName), null);
            }
            default -> {
                // rename / move / delete 都需要文件 id(rename 单个;move/delete 支持逗号分隔多个)
                List<Long> ids = parseFileIds(a);
                if (ids.isEmpty()) {
                    return new ToolOutcome("ERROR: " + action + " 需要 id 参数(文件 id,先 action=list 查看;"
                            + "move/delete 支持逗号分隔多个 id)。示例:{\"action\": \"" + action + "\", \"id\": \"12\"}",
                            null, null, false);
                }
                if ("rename".equals(action)) {
                    String newName = a.path("name").asText(null);
                    if (newName == null || newName.isBlank()) {
                        return new ToolOutcome("ERROR: rename 需要 name 参数(新文件名,含扩展名)。"
                                + "示例:{\"action\": \"rename\", \"id\": \"12\", \"name\": \"2026合同.pdf\"}", null, null, false);
                    }
                    if (ids.size() > 1) {
                        return new ToolOutcome("ERROR: rename 一次只能改一个文件(逐个改,或告诉我批量命名规则)",
                                null, null, false);
                    }
                    return bounded(fileToolClient.rename(ids.get(0), newName), null);
                }
                if ("move".equals(action)) {
                    Long folderId = a.path("folderId").isNumber() ? a.path("folderId").asLong() : null;
                    return bounded(fileToolClient.move(ids, folderId), null);
                }
                // delete(软删,进回收站)
                return bounded(fileToolClient.delete(ids), null);
            }
        }
    }

    /** 解析文件 id 参数:id / target 可以是数字/数字字符串/逗号分隔字符串(模型方言兼容)。 */
    private static List<Long> parseFileIds(JsonNode a) {
        List<Long> out = new java.util.ArrayList<>();
        JsonNode idNode = a.path("id");
        if (idNode.isMissingNode() || idNode.isNull()) {
            idNode = a.path("target"); // 模型常把 id 写进 target(与其他 manage_* 工具一致)
        }
        if (idNode.isNumber()) {
            out.add(idNode.asLong());
        } else if (idNode.isTextual()) {
            for (String part : idNode.asText("").split("[,\\s]+")) {
                if (part.matches("\\d+")) {
                    out.add(Long.parseLong(part));
                }
            }
        } else if (idNode.isArray()) {
            for (JsonNode n : idNode) {
                if (n.isNumber()) {
                    out.add(n.asLong());
                } else if (n.isTextual() && n.asText("").matches("\\d+")) {
                    out.add(Long.parseLong(n.asText("")));
                }
            }
        }
        return out;
    }

    /** manage_workspace handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageWorkspace(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (agentWorkspaceService == null) {
            return new ToolOutcome("ERROR: 工作区能力未启用(服务未配置)", null, null, false);
        }
        String action = RiskClassifier.normalizeWorkspaceAction(parsed.datasourceAction());
        if (!java.util.Set.of("list", "read", "write", "append", "delete", "import", "move", "copy", "mkdir", "edit")
                .contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 "
                    + "list / read / write / append / delete / import / move / copy / mkdir / edit", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            // 参数方言兼容(2026-09-18 复盘数据驱动):模型对「读文件」的第一直觉
            // 参数名是 filename/file(实测 12 次失败全是 filename)——harness 层吸收
            // 模型方言,别让用户为参数名教学买单。与 RiskClassifier.workspacePathOf
            // 同一别名序(2026-09-20 统一):权限判定与实际执行必须看到同一路径。
            String path = RiskClassifier.workspacePathOf(a);
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
                        sb.append(f.directory() ? "[目录] " : "").append(f.path());
                        if (f.directory()) {
                            // 目录带摘要(文件数+总大小):模型判断"素材在不在"不必再跑 PowerShell 数
                            sb.append(" (").append(f.fileCount()).append(" 个文件, ")
                                    .append(FileToolClient.formatSize(f.size())).append(")");
                        } else {
                            sb.append(" (").append(f.size()).append("B, ").append(f.modifiedAt()).append(")");
                        }
                        sb.append('\n');
                    }
                    yield sb.toString();
                }
                case "read" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数。相对路径=工作区内(如 USER.md);绝对路径可读整机(如 D:/projects/x/README.md)"
                                + ";路径本身就是相对工作区解析的,不要再拼工作区目录名(避免 agent-workspace/agent-workspace 这类重复)";
                    }
                    // 分段读(设计 §5.3 大结果回读):offset/limit 给截断后的续读出路
                    Integer readOffset = a.path("offset").isNumber() ? a.path("offset").asInt() : null;
                    if (readOffset != null) {
                        Integer readLimit = a.path("limit").isNumber() ? a.path("limit").asInt() : 500;
                        yield agentWorkspaceService.readRangeAny(path, readOffset, readLimit);
                    }
                    yield agentWorkspaceService.readAny(path);
                }
                case "write" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数(相对=工作区内,如 USER.md;绝对=整机)。"
                                + "示例:{\"action\": \"write\", \"path\": \"USER.md\", \"content\": \"…\"}";
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
                        yield "ERROR: 缺少 path 参数(相对=工作区内,如 memory/2026-09-18.md)。"
                                + "示例:{\"action\": \"append\", \"path\": \"memory/2026-09-18.md\", \"content\": \"…\"}";
                    }
                    String content = a.path("content").asText(null);
                    if (content == null || content.isBlank()) {
                        yield "ERROR: 缺少 content 参数(要追加的内容)";
                    }
                    int written = agentWorkspaceService.appendAny(path, content);
                    yield "已追加 " + written + " 字符到 " + path;
                }
                case "edit" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: edit 需要 path(要编辑的文件)。"
                                + "示例:{\"action\": \"edit\", \"path\": \"MEMORY.md\", \"old_string\": \"…\", \"new_string\": \"…\"}";
                    }
                    String oldString = a.path("old_string").asText(null);
                    String newString = a.path("new_string").asText(null);
                    if (oldString == null || oldString.isEmpty()) {
                        yield "ERROR: edit 需要 old_string 参数(要被替换的原文,必须与文件内容精确一致,含缩进;"
                                + "在文件中必须唯一出现)。若要整文件重写请改用 write";
                    }
                    if (newString == null) {
                        yield "ERROR: edit 需要 new_string 参数(替换后的新文本;留空字符串=删除该段)";
                    }
                    yield agentWorkspaceService.editAny(path, oldString, newString);
                }
                case "import" -> importFromUrl(a, path);
                case "move" -> {
                    // 源/目标:path 是源,to 是目标(与 write 的 path 语义一致)
                    String to = a.path("to").asText(null);
                    if (path == null || path.isBlank()) {
                        yield "ERROR: move 需要 path(源路径)与 to(目标路径);移动不覆盖已存在的目标";
                    }
                    if (to == null || to.isBlank()) {
                        yield "ERROR: move 需要 to 参数(目标路径;目标为已存在目录时移入该目录)";
                    }
                    yield agentWorkspaceService.moveAny(path, to);
                }
                case "mkdir" -> {
                    // 建目录(write/move 会自动建父目录,但空目录需要显式创建)
                    if (path == null || path.isBlank()) {
                        yield "ERROR: mkdir 需要 path 参数(要创建的目录路径)。"
                                + "示例:{\"action\": \"mkdir\", \"path\": \"photos/2026-09-18\"}";
                    }
                    yield agentWorkspaceService.mkdirAny(path);
                }
                case "copy" -> {
                    String to = a.path("to").asText(null);
                    if (path == null || path.isBlank()) {
                        yield "ERROR: copy 需要 path(源路径)与 to(目标路径);复制不覆盖已存在的目标";
                    }
                    if (to == null || to.isBlank()) {
                        yield "ERROR: copy 需要 to 参数(目标路径;目标为已存在目录时复制进该目录)";
                    }
                    yield agentWorkspaceService.copyAny(path, to);
                }
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

    /** manage_mcp handler(2026-09-17 从 executeTool 拆出;内部再拆 list/register/目标操作三段)。 */
    ToolOutcome execManageMcp(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
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
            if ("tools".equals(action)) {
                return mcpToolsResult(a);
            }
            if ("call".equals(action)) {
                return mcpCallResult(a);
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
                    .append("lazy".equalsIgnoreCase(s.toolPolicy()) ? " · lazy(按需)" : "")
                    .append('\n');
        }
        return new ToolOutcome(sb.toString(), null, null, false);
    }

    /**
     * manage_mcp action=tools:某服务器的工具清单(读缓存快照,不触发远端)。
     * 带 tool 参数时返回该工具的完整 inputSchema(设计 §6:lazy 路径
     * 「能力摘要 → 找到工具 → 读取完整参数说明 → 校验并调用」——
     * 缓存里已有 schema,调用前用它精确构造参数,不靠猜)。
     */
    private ToolOutcome mcpToolsResult(JsonNode a) {
        String target = Texts.firstNonNull(a.path("target").asText(null),
                Texts.firstNonNull(a.path("name").asText(null), a.path("server").asText(null)));
        if (target == null || target.isBlank()) {
            return new ToolOutcome("ERROR: tools 需要 target 参数(服务器名或 id)。可先用 action=list 查看",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器 \"" + target + "\"。可用服务器:\n"
                    + mcpServerService.list(), null, null, false);
        }
        List<McpServerService.ToolEntry> entries = mcpServerService.cachedTools(server.id());
        if (entries == null || entries.isEmpty()) {
            return new ToolOutcome("(服务器「" + server.name() + "」还没有工具清单快照——"
                    + "先用 action=refresh target=" + server.name() + " 拉取)", null, null, false);
        }
        // 单工具 schema 模式(带 tool 参数):完整参数说明,供精确构造调用
        String toolName = Texts.firstNonNull(a.path("tool").asText(null), a.path("toolName").asText(null));
        if (toolName != null && !toolName.isBlank()) {
            for (McpServerService.ToolEntry t : entries) {
                if (toolName.trim().equals(t.name())) {
                    StringBuilder sb = new StringBuilder("服务器「").append(server.name())
                            .append("」的工具 ").append(t.name()).append(" 完整说明:\n");
                    sb.append("描述: ").append(t.description() == null || t.description().isBlank()
                            ? "(无描述)" : t.description()).append('\n');
                    sb.append("参数 schema(按此构造 arguments;required 中的字段必填):\n");
                    sb.append(t.inputSchema() == null ? "(该工具未声明参数 schema——按描述调用,不确定时传空对象 {})"
                            : t.inputSchema().toPrettyString());
                    return bounded(sb.toString(), "工具说明 " + t.name());
                }
            }
            return new ToolOutcome("ERROR: 服务器「" + server.name() + "」没有名为 \"" + toolName
                    + "\" 的工具(以 action=tools 清单为准,不要凭记忆拼写)", null, null, false);
        }
        StringBuilder sb = new StringBuilder("服务器「").append(server.name()).append("」的工具清单(共 ")
                .append(entries.size()).append(" 个;lazy 服务器用 action=call 按名调用;"
                        + "调用前可 action=tools target=" + server.name() + " tool=<工具名> 查看完整参数说明):\n");
        for (McpServerService.ToolEntry t : entries) {
            sb.append("- ").append(t.name());
            String desc = t.description() == null ? "" : t.description();
            if (!desc.isBlank()) {
                sb.append(": ").append(Texts.abbreviate(desc, 160));
            }
            sb.append('\n');
        }
        return bounded(sb.toString(), "工具清单 " + entries.size() + " 个");
    }

    /**
     * manage_mcp action=call:按名调用工具(lazy 服务器的使用通道;eager 服务器
     * 也可用,等价于 mcp__&lt;server&gt;__&lt;tool&gt; 挂载调用)。
     * 参数:target=服务器, tool=工具名, arguments=工具参数 JSON 字符串或对象。
     */
    private ToolOutcome mcpCallResult(JsonNode a) {
        String target = Texts.firstNonNull(a.path("target").asText(null), a.path("server").asText(null));
        String tool = Texts.firstNonNull(a.path("tool").asText(null), a.path("toolName").asText(null));
        if (target == null || target.isBlank() || tool == null || tool.isBlank()) {
            return new ToolOutcome("ERROR: call 需要 target(服务器名或 id)与 tool(工具名)。"
                    + "示例:{\"action\": \"call\", \"target\": \"github\", \"tool\": \"get_me\", \"arguments\": \"{}\"}",
                    null, null, false);
        }
        McpServerService.ServerView server = mcpServerService.findByNameOrId(target);
        if (server == null) {
            return new ToolOutcome("ERROR: 找不到 MCP 服务器 \"" + target + "\"。可用服务器:\n"
                    + mcpServerService.list(), null, null, false);
        }
        if (!server.enabled()) {
            return new ToolOutcome("ERROR: 服务器「" + server.name() + "」已停用——先 action=enable target="
                    + server.name(), null, null, false);
        }
        // arguments 兼容三种形态:对象(直接透传)/JSON 字符串/省略(空对象)
        JsonNode argsNode = a.path("arguments");
        String argsJson;
        if (argsNode.isMissingNode() || argsNode.isNull()) {
            argsJson = "{}";
        } else if (argsNode.isObject()) {
            argsJson = argsNode.toString();
        } else if (argsNode.isTextual()) {
            String raw = argsNode.asText("");
            // 字符串形态必须是合法 JSON 对象(模型常把 arguments 写成 JSON 字符串)
            try {
                JsonNode parsed = objectMapper.readTree(raw.isBlank() ? "{}" : raw);
                argsJson = parsed.isObject() ? parsed.toString() : "{}";
            } catch (Exception e) {
                return new ToolOutcome("ERROR: arguments 不是合法 JSON: " + Texts.abbreviate(raw, 120)
                        + "。示例:{\"action\": \"call\", \"target\": \"github\", \"tool\": \"get_me\", \"arguments\": \"{}\"}",
                        null, null, false);
            }
        } else {
            return new ToolOutcome("ERROR: arguments 必须是对象或 JSON 字符串", null, null, false);
        }
        McpServerService.McpToolResult mcpResult = mcpServerService.callToolRich(server.id(), tool.trim(), argsJson);
        if (galleryPrefetcher != null && !mcpResult.isError()) {
            galleryPrefetcher.prefetchFromToolResult(mcpResult.text());
        }
        ToolOutcome outcome = bounded(mcpResult.text(), "MCP " + server.name() + "." + tool.trim() + " 执行完成");
        // 结果未知(调用已发出但中断):与挂载工具同语义透传(设计 §5.2)
        return mcpResult.images().isEmpty()
                ? (mcpResult.unknown()
                        ? new ToolOutcome(outcome.content(), outcome.summary(), outcome.rowCount(),
                                outcome.truncated(), outcome.images(), true)
                        : outcome)
                : new ToolOutcome(outcome.content(), outcome.summary(), outcome.rowCount(),
                        outcome.truncated(), mcpResult.images(), mcpResult.unknown());
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
        // 方言归一化(2026-09-18:实测模型写 transport="http"):与分类器共用
        rTransport = RiskClassifier.normalizeMcpTransport(rTransport);
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
                boolean lazy = "lazy".equalsIgnoreCase(server.toolPolicy());
                yield "已连接「" + server.name() + "」,发现 " + toolEntries.size() + " 个工具"
                        + (toolEntries.isEmpty() ? "" : ": " + formatToolNames(toolEntries)
                        + (toolEntries.size() > 10 ? " 等" : ""))
                        + (lazy ? "(lazy 策略:用 action=tools 查清单、action=call 调用)"
                                : "(已挂载为 mcp__" + server.name() + "__*,下轮可直接调用)");
            }
            case "enable" -> mcpServerService.setEnabled(server.id(), true)
                    ? "已启用「" + server.name() + "」。工具将在下轮对话挂载(缓存过工具清单则立即可用)"
                    : "ERROR: 启用失败,服务器可能已被删除";
            case "disable" -> mcpServerService.setEnabled(server.id(), false)
                    ? "已停用「" + server.name() + "」。其工具不再挂载"
                    : "ERROR: 停用失败,服务器可能已被删除";
            case "setpolicy" -> {
                String policy = a.path("toolPolicy").asText(null);
                boolean ok = mcpServerService.setToolPolicy(server.id(), policy);
                yield ok ? "已把「" + server.name() + "」的工具加载策略设为 " + policy
                        + ("lazy".equalsIgnoreCase(policy)
                        ? "(其工具不再挂载;用 action=tools 查清单、action=call 调用,省每轮上下文)"
                        : "(其工具重新挂载为 mcp__" + server.name() + "__*,下轮可直接调用)")
                        : "ERROR: 策略更新失败,服务器可能已被删除";
            }
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
            // 部分成功(设计 §5.2):有失败项时明确标注,不显示为全量成功——
            // 模型据此只处理未完成项,而不是声称"全部完成"
            if (report.failed() > 0) {
                sb.append("\n(部分成功:").append(report.failed())
                        .append(" 个文件失败——可只重试失败项,已成功的会跳过)");
            }
            ToolOutcome fetchOutcome = bounded(sb.toString(), "下载 " + report.downloaded() + "/" + report.total()
                    + (report.failed() > 0 ? ",失败 " + report.failed() : ""));
            return report.failed() > 0
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
            return bounded(knowledgeManageClient.search(query, topK), null);
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
        if (!java.util.Set.of("list", "index", "remove", "reindex", "stats").contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 list / index / remove / reindex / stats",
                    null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            return switch (action) {
                case "list" -> bounded(knowledgeManageClient.list(a.path("filter").asText(null)), null);
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
                    yield bounded("remove".equals(action)
                            ? knowledgeManageClient.remove(docId)
                            : knowledgeManageClient.reindex(docId), null);
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
                    yield bounded(automationManageClient.create(rName, trigger, prompt), null);
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

    /** 从 TSV 渲染推导一行行数摘要("(3 rows, 12ms)" 页脚)。 */
    private String summarizeRows(String tsv) {
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
     * <p>链路自动选择(2026-09-17 晚):目标属于中继域名时走
     * {@link RelayMediaRouter} 重写——在家自动走内网 8902(快一个量级),
     * 失败立即回退公网。此前 import 单文件下载没接路由器,「在家下载相册」
     * 走的是公网绕行路径。
     *
     * @param url      下载地址
     * @param maxBytes 允许的最大字节数
     * @return 文件字节
     * @throws IllegalArgumentException 超限或下载失败(消息面向用户可读)
     */
    private byte[] downloadBounded(String url, long maxBytes) throws Exception {
        String effective = relayMediaRouter == null ? url : relayMediaRouter.preferLan(url);
        try {
            return downloadBoundedDirect(effective, url, maxBytes);
        } catch (Exception e) {
            if (!effective.equals(url) && relayMediaRouter != null) {
                relayMediaRouter.reportLanFailure();
                log.info("局域网下载失败,回退公网: {} ({})", url, e.getMessage());
                return downloadBoundedDirect(url, url, maxBytes);
            }
            throw e;
        }
    }

    /** 单次下载(不做链路选择);displayUrl 仅用于错误消息。 */
    private byte[] downloadBoundedDirect(String effective, String displayUrl, long maxBytes) throws Exception {
        java.net.http.HttpClient.Builder cb = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1) // 明文链路(局域网 8902)防 h2c 升级探测
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);
        java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder.addressFor(effective);
        if (proxyAddr != null) {
            cb.proxy(java.net.ProxySelector.of(proxyAddr));
        }
        java.net.http.HttpResponse<java.io.InputStream> resp = cb.build().send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(effective))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "Nora-Agent/1.0")
                        .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalArgumentException("下载失败 HTTP " + resp.statusCode() + "(" + Texts.abbreviate(displayUrl, 100) + ")");
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
        } else if (target.endsWith("/") || target.endsWith("\\")
                || (agentWorkspaceService != null && agentWorkspaceService.isDirectoryAny(target))) {
            // 目录语义兼容(2026-09-18 文件工具分析):模型写 path="imports/" 或
            // 已存在的目录(想把文件存进去),自动补文件名——比报「目标是目录」可操作
            String name = a.path("filename").asText(null);
            if (name == null || name.isBlank()) {
                name = inferFilename(url);
            }
            target = target.replaceAll("[/\\\\]+$", "") + "/" + name;
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

    /** 一次执行的工具调用:有界内容 + 面向 UI 的元数据。 */
    record ToolOutcome(String content, String summary, Integer rowCount, Boolean truncated,
                        /** 图片附件(MCP 工具返回的 image 块);空 = 纯文本 */
                        java.util.List<McpServerService.McpToolResult.ImageBlock> images,
                        /** 结果未知(调用已发出但中断,远端可能已生效;设计 §5.2):
                         *  与普通失败区分——前端橙色警示、运行终态记 partial。 */
                        boolean unknown,
                        /** 部分成功(批量任务有成功也有失败;设计 §5.2):
                         *  保留成功/失败/跳过区别,不显示为全量成功。 */
                        boolean partial) {

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
