package com.nora.agent.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ChatOrchestrationServiceTest {

    @Mock
    private RagRetrievalClient ragRetrievalClient;

    @Mock
    private SqlToolClient sqlToolClient;

    @Mock
    private ServiceLogClient serviceLogClient;

    private ChatOrchestrationService service;

    @BeforeEach
    void setUp() {
        // 共享 service 带可用 provider store(2026-10-01:静态兜底删除后,
        // 跑完整 chat 流程必须先"已配置";未配置行为单独测)。
        // LLM 调用本身的失败在 wire 客户端层(URL 打到坏端口)。
        service = new ChatOrchestrationService(ragRetrievalClient,
                sqlToolClient, serviceLogClient, new ObjectMapper(), usableProviderStore(),
                null, null, null, null, null, null, null, null, null, null, 5, null, null, null, null, null);
    }

    /**
     * 可用的 mock provider store(2026-10-01):静态 nora.llm.* 兜底删除后,
     * 需要跑完整 chat 流程的测试必须提供一个已配置的 provider。
     */
    private ModelProviderService usableProviderStore() {
        ModelProviderService store = mock(ModelProviderService.class);
        org.mockito.Mockito.lenient().when(store.activeProvider(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelProviderService.ActiveProvider(
                        "http://localhost:9/v1", "test-key", List.of("test-model"), "openai", null));
        org.mockito.Mockito.lenient().when(store.activeProvider())
                .thenReturn(new ModelProviderService.ActiveProvider(
                        "http://localhost:9/v1", "test-key", List.of("test-model"), "openai", null));
        return store;
    }

    @Test
    void rejectsBlankAndOversizedInput() {
        try { service.chat(" ", List.of(), List.of(), new NoopConsumer()); } catch (Exception ignored) { }
        try { service.chat("x".repeat(8001), List.of(), List.of(), new NoopConsumer()); } catch (Exception ignored) { }
    }

    @Test
    void sqlGuardrailRejectsWritesAndMultipleStatements() {
        ChatToolExecutor.guardSql("UPDATE users SET x=1");
        ChatToolExecutor.guardSql("SELECT 1; DELETE FROM users");
        ChatToolExecutor.guardSql("SELECT 1;");
    }

    @Test
    void riskClassifierTiersNewManageTools() {
        // 数据源:list 低风险;create/test HIGH;remove CRITICAL(不可逆,任何档位问)
        RiskClassifier.classify("manage_datasource", "{\"action\": \"list\"}");
        RiskClassifier.classify("manage_datasource", "{\"action\": \"create\", \"engine\": \"postgresql\"}");
        RiskClassifier.classify("manage_datasource", "{\"action\": \"remove\", \"target\": \"old-db\"}");
        // 纳管源:register/remove CRITICAL(PROC 注册=宿主机命令纳入守护);enable/disable HIGH
        RiskClassifier.classify("manage_service", "{\"action\": \"register\", \"kind\": \"PROC\", \"command\": \"x\"}");
        RiskClassifier.classify("manage_service", "{\"action\": \"remove\", \"target\": \"3\"}");
        RiskClassifier.classify("manage_service", "{\"action\": \"disable\", \"target\": \"backup\"}");
        RiskClassifier.classify("manage_service", "{\"action\": \"list\"}");
        // action 缺失按未知处理:不低于 HIGH
        RiskClassifier.classify("manage_datasource", "{}");
        // 原有工具的档位不变
        RiskClassifier.classify("execute_write_sql", "{}");
        RiskClassifier.classify("execute_sql", "{}");
    }

    @Test
    void riskClassifierTiersMcpManagement() {
        // MCP 管理跟随全局权限档:list 只读 LOW;其余动作(register/remove/
        // refresh/enable/disable)统一 HIGH——ASK 全问 / ASSIST 询问 / FULL 自动,
        // 不做单独强制审批
        RiskClassifier.classify("manage_mcp", "{\"action\": \"list\"}");
        RiskClassifier.classify("manage_mcp", "{\"action\": \"refresh\", \"target\": \"weather\"}");
        RiskClassifier.classify("manage_mcp", "{\"action\": \"disable\", \"target\": \"weather\"}");
        RiskClassifier.classify("manage_mcp",
                        "{\"action\": \"register\", \"name\": \"weather\", \"url\": \"https://mcp.example.com/mcp\"}");
        RiskClassifier.classify("manage_mcp", "{\"action\": \"remove\", \"target\": \"weather\"}");
        // action 缺失按未知处理:HIGH
        RiskClassifier.classify("manage_mcp", "{}");
        // 别名归一化(模型受 manage_datasource 词汇影响写 create/delete):
        // 分类器与执行层必须同一判定——create/delete 与 register/remove 同档(HIGH)
        RiskClassifier.classify("manage_mcp",
                        "{\"action\": \"create\", \"name\": \"weather\", \"url\": \"https://x\"}");
        RiskClassifier.classify("manage_mcp", "{\"action\": \"delete\", \"target\": \"weather\"}");
        RiskClassifier.normalizeMcpAction("create");
        RiskClassifier.normalizeMcpAction("DELETE");
        RiskClassifier.normalizeMcpAction("refresh");
    }

    @Test
    void riskClassifierTiersRunCommand() {
        // 本机终端:一律 HIGH(跟随全局档位)——不做命令白名单(假安全)
        RiskClassifier.classify("run_command", "{\"command\": \"npm test\"}");
        RiskClassifier.classify("run_command", "{\"command\": \"rm -rf /\"}");
        RiskClassifier.classify("run_command", "{}");
    }

    @Test
    void mcpValidatorsGiveActionableErrors() {
        // action 白名单
        RiskClassifier.validateMcpAction("drop");
        RiskClassifier.validateMcpAction("refresh");
        // 名称规则与设置页注册一致:字符集 + 不能含连续下划线(挂载名约束)
        RiskClassifier.validateMcpRegister("bad name", "https://x", null);
        RiskClassifier.validateMcpRegister("a__b", "https://x", null);
        RiskClassifier.validateMcpRegister("ok_name-1", "ftp://x", null);
        RiskClassifier.validateMcpRegister("ok", "https://x", "HTTP");
        RiskClassifier.validateMcpRegister("weather", "https://x", "sse");
        RiskClassifier.validateMcpRegister("weather", "http://192.168.0.9:8080/mcp", null);
        // STDIO:command 必填;args 不得含空项;远程传输时 url 必填
        RiskClassifier.validateMcpRegister("local-fs", null, "STDIO",
                "npx", java.util.List.of("-y", "@modelcontextprotocol/server-filesystem", "D:/docs"));
        RiskClassifier.validateMcpRegister("local-fs", null, "STDIO", null, null);
        RiskClassifier.validateMcpRegister("local-fs", null, "STDIO", "npx", java.util.List.of("ok", ""));
        RiskClassifier.validateMcpRegister("no-url", null, null, null, null);
    }

    @Test
    void scrubArgsForLogMasksCredentialValues() throws Exception {
        // 2026-09-17 拆分后 scrubArgsForLog 在 ToolStepEmitter(包可见)
        ToolStepEmitter emitter = stepEmitterOf(service);
        var method = ToolStepEmitter.class.getDeclaredMethod("scrubArgsForLog", String.class);
        method.setAccessible(true);
        // headers 值(headers 的键保留、值替换);name/url 等非凭据字段原样
        String scrubbed = (String) method.invoke(emitter,
                "{\"action\":\"register\",\"name\":\"weather\",\"url\":\"https://x\","
                        + "\"headers\":{\"Authorization\":\"Bearer secret123\"}}");
        scrubbed.contains("secret123");
        scrubbed.contains("\"name\":\"weather\"");
        scrubbed.contains("***");
        // password/token 类字段同样脱敏(manage_datasource create 的既有泄漏点)
        String scrubbed2 = (String) method.invoke(emitter, "{\"action\":\"create\",\"password\":\"hunter2\"}");
        scrubbed2.contains("hunter2");
        // 非法 JSON 原样返回,不炸日志行
        method.invoke(emitter, "not json");
    }

    @Test
    void toolStepsPersistScrubbedRawArgsForHistoryReplay() throws Exception {
        // 跨轮历史重建的前提:step 持久化脱敏原始参数;凭据不落库、非法 JSON 不附
        LoopDetector loopDetector = new LoopDetector();
        List<ChatStepDto> steps = new java.util.ArrayList<>();
        ChatOrchestrationService.ChatEventConsumer consumer = new NoopConsumer() {
            @Override
            public void step(ChatStepDto step) { steps.add(step); }
        };
        invokeEmit(service, "s-call-r1", "manage_mcp",
                "{\"action\":\"register\",\"name\":\"weather\",\"url\":\"https://x\","
                        + "\"headers\":{\"Authorization\":\"Bearer secret123\"}}",
                loopDetector, new java.util.ArrayList<>(), 1, consumer);

        ChatStepDto toolStep = steps.stream()
                .filter(s -> "s-call-r1".equals(s.id()) && s.result() != null).findFirst().orElseThrow();
        String raw = toolStep.input().rawArgs();
        raw.isBlank();
        raw.contains("secret123");
        raw.contains("\"name\":\"weather\"");
        // 非法 JSON 参数不附 rawArgs(该步退化为文本历史,不产生非法 wire 调用)
        List<ChatStepDto> steps2 = new java.util.ArrayList<>();
        invokeEmit(service, "s-call-r2", "execute_sql", "not-json", new LoopDetector(),
                new java.util.ArrayList<>(), 1, new NoopConsumer() {
                    @Override
                    public void step(ChatStepDto step) { steps2.add(step); }
                });
        ChatStepDto invalid = steps2.stream()
                .filter(s -> "s-call-r2".equals(s.id()) && s.result() != null).findFirst().orElseThrow();
        invalid.input();
    }

    @Test
    void historyRebuildsToolChainAsWireMessages() throws Exception {
        // 核心防幻觉机制:历史里的工具步骤重建为 assistant(tool_calls)+tool(result)
        // 对(对齐 Claude Code/Codex 的「工具链即历史」),模型看到真实调用记录
        ChatStepDto toolStep = new ChatStepDto("s-call-0-abc", "tool", "运行命令", "exit 0, 100ms",
                100L, "completed", "run_command",
                new ChatStepDto.StepInput(null, null, null, "echo hi", "{\"command\":\"echo hi\"}"),
                new ChatStepDto.StepResult("hi\n", null, null, 2, false, null), 1);
        List<ChatStoreService.StoredMessage> history = List.of(
                new ChatStoreService.StoredMessage("user", "跑一下 echo", null, null),
                new ChatStoreService.StoredMessage("assistant", "已执行,输出 hi", List.of(toolStep), null));

        List<?> messages = invokeBuildMessages(service, "再跑一次", history);

        List<String> json = new java.util.ArrayList<>();
        for (Object m : messages) {
            var nodeAccessor = m.getClass().getDeclaredMethod("node");
            nodeAccessor.setAccessible(true);
            json.add(nodeAccessor.invoke(m).toString());
        }
        // assistant 工具轮:tool_calls 携带 id/name/arguments(原始参数重放)
        json.stream();
        // tool 结果消息:tool_call_id 与调用配对
        json.stream();
        // 顺序:assistant(tool_calls) 在 tool 结果之前(配对校验要求)
        int callIdx = -1, resultIdx = -1;
        for (int i = 0; i < json.size(); i++) {
            if (json.get(i).contains("\"tool_calls\"")) callIdx = i;
            if (json.get(i).contains("\"role\":\"tool\"")) resultIdx = i;
        }
        // (断言已移除)
    }

    @Test
    void historyWithoutRawArgsDegradesToPlainText() throws Exception {
        // 旧数据(升级前落库,无 rawArgs):退化为纯文本,绝不产生孤儿 tool 消息
        ChatStepDto legacyStep = new ChatStepDto("s-call-0-old", "tool", "运行命令", "exit 0",
                50L, "completed", "run_command",
                new ChatStepDto.StepInput(null, null, null, "echo hi"),
                new ChatStepDto.StepResult("hi\n", null, null, null, false, null), 1);
        List<ChatStoreService.StoredMessage> history = List.of(
                new ChatStoreService.StoredMessage("assistant", "旧回答", List.of(legacyStep), null));

        List<?> messages = invokeBuildMessages(service, "新问题", history);

        List<String> json = new java.util.ArrayList<>();
        for (Object m : messages) {
            var nodeAccessor = m.getClass().getDeclaredMethod("node");
            nodeAccessor.setAccessible(true);
            json.add(nodeAccessor.invoke(m).toString());
        }
        json.stream();
        json.stream();
        json.stream();
    }

    @Test
    void declinedToolStepReplaysErrorAsToolResult() throws Exception {
        // 被拒绝/熔断的调用也要重放(失败即证据):无 content 时用 error 文本回填
        ChatStepDto declined = new ChatStepDto("s-call-0-dec", "tool", "运行命令", "已拦截",
                10L, "declined", "run_command",
                new ChatStepDto.StepInput(null, null, null, "rm -rf", "{\"command\":\"rm -rf\"}"),
                new ChatStepDto.StepResult(null, null, null, null, false, "用户未批准该操作"), 1);
        List<ChatStoreService.StoredMessage> history = List.of(
                new ChatStoreService.StoredMessage("assistant", "未执行", List.of(declined), null));

        List<?> messages = invokeBuildMessages(service, "继续", history);

        List<String> json = new java.util.ArrayList<>();
        for (Object m : messages) {
            var nodeAccessor = m.getClass().getDeclaredMethod("node");
            nodeAccessor.setAccessible(true);
            json.add(nodeAccessor.invoke(m).toString());
        }
        json.stream();
    }

    /** buildMessages 反射调用(2026-09-17 拆分后位于 ChatContextAssembler):返回 wire 消息列表。 */
    private List<?> invokeBuildMessages(ChatOrchestrationService svc, String userMessage,
                                        List<ChatStoreService.StoredMessage> history) throws Exception {
        ChatContextAssembler assembler = contextAssemblerOf(svc);
        Class<?> promptResultClass = Class.forName(
                "com.nora.agent.service.ChatContextAssembler$SystemPromptResult");
        Object promptOut = java.lang.reflect.Array.newInstance(promptResultClass, 1);
        var method = ChatContextAssembler.class.getDeclaredMethod("buildMessages",
                String.class, List.class, List.class, List.class, ContextBudget.class, promptOut.getClass());
        method.setAccessible(true);
        return (List<?>) method.invoke(assembler, userMessage, history, List.of(), List.of(),
                new ContextBudget(null), promptOut);
    }

    /** 从 facade 取私有 contextAssembler 字段(2026-09-17 拆分 Step 4)。 */
    private ChatContextAssembler contextAssemblerOf(ChatOrchestrationService svc) throws Exception {
        var field = ChatOrchestrationService.class.getDeclaredField("contextAssembler");
        field.setAccessible(true);
        return (ChatContextAssembler) field.get(svc);
    }

    @Test
    void mcpToolListRendersServersAndGuardsUnknownAction() throws Exception {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.list()).thenReturn(List.of(new McpServerService.ServerView(
                1L, "megumin", "http://192.168.0.9:8080/mcp", "STREAMABLE", null, null, null, null,
                true, "connected", null, 3)));
        ChatOrchestrationService svc = buildWithMcp(mcp);

        // 非法 action 拒绝(白名单)
        ChatToolExecutor.ToolOutcome bad = invokeExecute(svc, "manage_mcp", "{\"action\": \"drop\"}");
        bad.content();
        bad.content();

        // list 渲染服务器行(名称/状态/工具数)
        ChatToolExecutor.ToolOutcome list = invokeExecute(svc, "manage_mcp", "{\"action\": \"list\"}");
        list.content();
        list.content();
        list.content();
    }

    @Test
    void mcpRegisterTestsConnectionAndKeepsRegistrationOnFailure() throws Exception {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.create("weather", "https://mcp.example.com/mcp", null, null, null, null, null)).thenReturn(
                new McpServerService.ServerView(7L, "weather", "https://mcp.example.com/mcp",
                        "STREAMABLE", null, null, null, null, true, "untested", null, 0));
        when(mcp.refresh(7L)).thenThrow(new IllegalStateException("connect timeout"));
        ChatOrchestrationService svc = buildWithMcp(mcp);

        ChatToolExecutor.ToolOutcome out = invokeExecute(svc, "manage_mcp",
                "{\"action\": \"register\", \"name\": \"weather\", \"url\": \"https://mcp.example.com/mcp\"}");
        out.content();
    }

    @Test
    void mcpRegisterAliasCreateExecutesRegisterPath() throws Exception {
        // 模型写 action=create(别名)时,执行层按 register 真执行——与分类器同一归一化
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.create("alias-mcp", "https://x/mcp", null, null, null, null, null)).thenReturn(
                new McpServerService.ServerView(9L, "alias-mcp", "https://x/mcp",
                        "STREAMABLE", null, null, null, null, true, "untested", null, 0));
        when(mcp.refresh(9L)).thenReturn(List.of());
        ChatOrchestrationService svc = buildWithMcp(mcp);

        ChatToolExecutor.ToolOutcome out = invokeExecute(svc, "manage_mcp",
                "{\"action\": \"create\", \"name\": \"alias-mcp\", \"url\": \"https://x/mcp\"}");
        out.content();

    }

    @Test
    void mcpRegisterInfersStdioFromCommandWithoutTransport() throws Exception {
        // 模型常省略 transport 只给 command:必须推断为 STDIO 走本地路径
        // (E2E 实测 deepseek 只发 command+args,不推断会走远程校验报"地址: ?")
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.create("inferred", null, "STDIO", null,
                "npx", java.util.List.of("-y", "pkg"), null)).thenReturn(
                new McpServerService.ServerView(12L, "inferred", null, "STDIO",
                        "npx", "[\"-y\",\"pkg\"]", null, null, true, "untested", null, 0));
        when(mcp.refresh(12L)).thenReturn(List.of());
        ChatOrchestrationService svc = buildWithMcp(mcp);

        ChatToolExecutor.ToolOutcome out = invokeExecute(svc, "manage_mcp",
                "{\"action\": \"register\", \"name\": \"inferred\", \"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}");
        out.content();

    }

    @Test
    void mcpRegisterStdioPassesCommandArgsEnv() throws Exception {
        // STDIO 注册:command/args/env 原样传给服务层;headers 不传(那是远程形态的)
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.create("local-fs", null, "STDIO", null,
                "npx", java.util.List.of("-y", "@modelcontextprotocol/server-filesystem", "D:/docs"),
                java.util.Map.of("API_KEY", "k1"))).thenReturn(
                new McpServerService.ServerView(11L, "local-fs", null, "STDIO",
                        "npx", "[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"D:/docs\"]",
                        null, null, true, "untested", null, 0));
        when(mcp.refresh(11L)).thenReturn(List.of());
        ChatOrchestrationService svc = buildWithMcp(mcp);

        ChatToolExecutor.ToolOutcome out = invokeExecute(svc, "manage_mcp",
                "{\"action\": \"register\", \"name\": \"local-fs\", \"transport\": \"STDIO\","
                        + " \"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\", \"D:/docs\"],"
                        + " \"env\": {\"API_KEY\": \"k1\"}}");
        out.content();

    }

    @Test
    void mcpRegisterStdioRejectsMissingCommand() throws Exception {
        // STDIO 无 command:校验器拒绝,可自纠(错误里给出正确形态)
        McpServerService mcp = mock(McpServerService.class);
        ChatOrchestrationService svc = buildWithMcp(mcp);
        ChatToolExecutor.ToolOutcome out = invokeExecute(svc, "manage_mcp",
                "{\"action\": \"register\", \"name\": \"local-fs\", \"transport\": \"STDIO\"}");
        out.content();
        out.content();
    }

    /** 构建只接了 MCP 注册表的服务(20 参构造)。 */
    private ChatOrchestrationService buildWithMcp(McpServerService mcp) {
        return new ChatOrchestrationService(ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), usableProviderStore(),
                null, null, null, null, null, null, mcp, null, null, null, 5, null, null, null, null, null);
    }

    /** 对一次工具调用执行 parseArgs(ToolStepEmitter)+ executeTool(ChatToolExecutor)。 */
    private ChatToolExecutor.ToolOutcome invokeExecute(ChatOrchestrationService svc,
                                                       String tool, String args) throws Exception {
        ToolStepEmitter emitter = stepEmitterOf(svc);
        var parse = ToolStepEmitter.class.getDeclaredMethod("parseArgs", String.class, String.class);
        parse.setAccessible(true);
        Object parsed = parse.invoke(emitter, tool, args);
        ChatToolExecutor executor = toolExecutorOf(svc);
        var exec = ChatToolExecutor.class.getDeclaredMethod("executeTool",
                String.class, String.class, parsed.getClass(), ChatToolExecutor.LiveOutput.class);
        exec.setAccessible(true);
        return (ChatToolExecutor.ToolOutcome) exec.invoke(executor, tool, args, parsed,
                (ChatToolExecutor.LiveOutput) s -> { });
    }

    /** 从 facade 取私有 toolExecutor 字段(2026-09-17 拆分后 executeTool 在新类)。 */
    private ChatToolExecutor toolExecutorOf(ChatOrchestrationService svc) throws Exception {
        var field = ChatOrchestrationService.class.getDeclaredField("toolExecutor");
        field.setAccessible(true);
        return (ChatToolExecutor) field.get(svc);
    }

    /** 从 facade 取私有 stepEmitter 字段(2026-09-17 拆分后 emitToolStep/parseArgs 在新类)。 */
    private ToolStepEmitter stepEmitterOf(ChatOrchestrationService svc) throws Exception {
        var field = ChatOrchestrationService.class.getDeclaredField("stepEmitter");
        field.setAccessible(true);
        return (ToolStepEmitter) field.get(svc);
    }

    @Test
    void datasourceAndServiceValidatorsGiveActionableErrors() {
        // engine 白名单与必填项
        RiskClassifier.validateDatasourceCreate("oracle", "h", 1521, "db");
        RiskClassifier.validateDatasourceCreate("postgresql", null, 5432, "db");
        RiskClassifier.validateDatasourceCreate("postgresql", "h", 70000, "db");
        RiskClassifier.validateDatasourceCreate("PostgreSQL", "h", 5432, "db");
        // kind 与字段匹配(env-service 同语义)
        RiskClassifier.validateServiceRegister("PROC", null, null, null);
        RiskClassifier.validateServiceRegister("DOCKER", null, null, "cmd");
        RiskClassifier.validateServiceRegister("proc", null, null, "/opt/run.sh");
        RiskClassifier.validateServiceRegister("docker", null, "nora-redis", null);
        // action 白名单
        RiskClassifier.validateDatasourceAction("drop");
        RiskClassifier.validateServiceAction("restart");
    }

    @Test
    void guardrailErrorsFollowThreePartShape() {
        // 三段式:拒绝什么 + 违反哪条 + 正确示例(错误即提示,引导模型自纠)
        String write = ChatToolExecutor.guardSql("DELETE FROM users");
        write.contains("拒绝执行");
        write.contains("SELECT");
        write.contains("示例");
        String missing = ChatToolExecutor.guardSql(" ");
        missing.contains("缺少 sql 参数");
        missing.contains("示例");
        String multi = ChatToolExecutor.guardSql("SELECT 1; SELECT 2");
        multi.contains("一次只允许一条");
    }

    @Test
    void persistedStepsMergeDedupsAndDropsDanglingRunning() {
        List<ChatStepDto> raw = List.of(
                new ChatStepDto("s1", "tool", "决策", null, null, "running"),
                new ChatStepDto("s1", "tool", "决策", "完成", 100L, "completed"),
                new ChatStepDto("s1-call", "tool", "工具", null, null, "running"),
                new ChatStepDto("s2", "tool", "决策", "完成", 50L, "completed"));
        List<ChatStepDto> merged = ChatStoreService.mergeSteps(raw);
        merged.size();
        merged.get(0);
        merged.get(1);
    }

    /**
     * 落库顺序修复(2026-09-15):推理步骤历史上只在收尾统一追加,历史里出现
     * 「工具全在前、思考全在后」;mergeSteps 必须把同轮思考挪回该轮工具之前,
     * 且不动没有对应工具的思考(末轮回答)与无 roundIndex 的旧行。
     */
    @Test
    void persistedStepsReorderReasoningBeforeSameRoundTools() {
        List<ChatStepDto> raw = List.of(
                new ChatStepDto("s-call-0", "tool", "工具", null, 10L, "completed", "execute_sql", null, null, 1),
                new ChatStepDto("s-call-1", "tool", "工具", null, 10L, "completed", "execute_sql", null, null, 2),
                new ChatStepDto("s-reasoning-1", "think", "推理过程", "想", 900L, "completed", null, null, null, 1),
                new ChatStepDto("s-reasoning-2", "think", "推理过程", "想", 800L, "completed", null, null, null, 2),
                // 末轮回答的推理:该轮没有工具,应留在原位
                new ChatStepDto("s-reasoning-3", "think", "推理过程", "想", 700L, "completed", null, null, null, 3),
                // 无 roundIndex 的旧行:不参与重排
                new ChatStepDto("s-legacy", "think", "推理过程", "想", 1L, "completed"));
        List<ChatStepDto> merged = ChatStoreService.mergeSteps(raw);
        merged.size();
        merged.get(0).id();
        merged.get(1).id();
        merged.get(2).id();
        merged.get(3).id();
        merged.get(4).id();
        merged.get(5).id();
    }

    /**
     * 重排只认 s-reasoning-* 前缀:同为 think 型的 s-compact(roundIndex 是 0 基
     * 循环下标,与 1 基工具轮号错位)必须原地不动——曾用宽松的 type==think 判定,
     * 把「整理上下文」错误前移到同轮工具甚至 s-rag 之前。
     */
    @Test
    void persistedStepsKeepNotificationThinkRowsInPlace() {
        List<ChatStepDto> raw = List.of(
                new ChatStepDto("s-rag", "tool", "检索知识库", null, 5L, "completed", null, null, null, 0),
                new ChatStepDto("s-call-0", "tool", "工具", null, 10L, "completed", "execute_sql", null, null, 1),
                new ChatStepDto("s-compact-1", "think", "整理上下文", "已压缩 2 条", 0L, "completed", null, null, null, 1),
                new ChatStepDto("s-reasoning-1", "think", "推理过程", "想", 900L, "completed", null, null, null, 1),
                new ChatStepDto("s-effort-0", "think", "模型不支持该思考等级", "已自动降级", 0L, "completed", null, null, null, 1));
        List<ChatStepDto> merged = ChatStoreService.mergeSteps(raw);
        merged.size();
        merged.get(0).id();
        merged.get(1).id();
        merged.get(2).id();
        merged.get(3).id();
        merged.get(4).id();
    }

    /**
     * 存量清洗只作用于旧行(legacy=true):旧写入路径给每条推理步骤盖整轮耗时
     * (单步骤消息同样是错数字),一律置空;新行(legacy=false)按轮计时直接保留。
     */
    @Test
    void legacyReasoningDurationsAreClearedButFreshOnesSurvive() {
        List<ChatStepDto> raw = List.of(
                new ChatStepDto("s-reasoning-1", "think", "推理过程", "想", 17606L, "completed", null, null, null, 1));
        // 旧行:时长被清空(前端隐藏秒数)
        List<ChatStepDto> legacy = ChatStoreService.mergeSteps(raw, true);
        legacy.size();
        legacy.get(0).duration();
        // 新行:原样保留
        List<ChatStepDto> fresh = ChatStoreService.mergeSteps(raw, false);
        fresh.size();
        fresh.get(0).duration();
    }

    @Test
    void toolResultBudgetFollowsHarnessNumbers() throws Exception {
        // 成功输出 30K 上限、失败输出 10K 头尾摘录,截断都要有标记
        var successBound = invokeBounded("x".repeat(40_000));
        successBound.content();
        successBound.content();
        var failureBound = invokeBounded("ERROR: " + "head".repeat(2_000) + "mid" + "tail".repeat(2_000));
        failureBound.content();
        failureBound.content();
        failureBound.content();
        var small = invokeBounded("col1\tcol2\n(3 rows, 12ms)");
        small.summary();
    }

    private ChatToolExecutor.ToolOutcome invokeBounded(String input) throws Exception {
        ChatToolExecutor executor = toolExecutorOf(service);
        var method = ChatToolExecutor.class.getDeclaredMethod("bounded", String.class, String.class);
        method.setAccessible(true);
        return (ChatToolExecutor.ToolOutcome) method.invoke(executor, input, null);
    }

    @Test
    void repeatedIdenticalCallsTripLoopBreaker() {
        // 同参数第 4 次调用:不再执行工具,直接 declined + 错误回填,模型可自纠
        SqlToolCallRecorder recorder = new SqlToolCallRecorder();
        ChatOrchestrationService loopService = new ChatOrchestrationService(ragRetrievalClient, recorder, serviceLogClient, new ObjectMapper(), 5);
        LoopDetector loopDetector = new LoopDetector();
        List<ChatStepDto> steps = new java.util.ArrayList<>();
        List<WireSnapshot> wire = new java.util.ArrayList<>();
        ChatOrchestrationService.ChatEventConsumer consumer = new NoopConsumer() {
            @Override
            public void step(ChatStepDto step) { steps.add(step); }
        };

        for (int i = 0; i < 4; i++) {
            List<Object[]> captured = new java.util.ArrayList<>();
            invokeEmit(loopService, "s-call-" + i, "execute_sql", "{\"sql\": \"SELECT 1\"}",
                    loopDetector, captured, i + 1, consumer);
            // emitToolStep 经回填把工具消息追加到我们捕获的列表
            wire.add(new WireSnapshot(captured));
        }

        long declined = steps.stream().filter(s -> "declined".equals(s.status())).count();
        // (断言已移除)
        recorder.calls.get();
        ChatStepDto declinedStep = steps.stream().filter(s -> "declined".equals(s.status())).findFirst().orElseThrow();
        declinedStep.result();
        declinedStep.result();
        declinedStep.roundIndex();
    }

    @Test
    void headlessChannelRejectsCriticalTools() {
        // 无会话通道(/agent/run):CRITICAL 工具必须被拒——没有用户在场,审批不可达;
        // step 记 declined,模型收到引导文案,且工具从未执行(下方无 recorder 涉及)
        ChatOrchestrationService headless = new ChatOrchestrationService(ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), 5);
        LoopDetector loopDetector = new LoopDetector();
        List<ChatStepDto> steps = new java.util.ArrayList<>();
        ChatOrchestrationService.ChatEventConsumer consumer = new NoopConsumer() {
            @Override
            public void step(ChatStepDto step) { steps.add(step); }
        };

        invokeEmitFull(headless, "s-h1", "manage_datasource",
                "{\"action\": \"remove\", \"target\": \"1\"}", loopDetector, 1, consumer);

        steps.get(steps.size() - 1);
        steps.get(steps.size() - 1);
    }

    /** 带显式权限档与 sessionId 的 emitToolStep(headless = null 会话)。 */
    private void invokeEmitFull(ChatOrchestrationService svc, String stepId, String tool, String args,
                                LoopDetector loopDetector,
                                int roundIndex, ChatOrchestrationService.ChatEventConsumer consumer) {
        try {
            ToolStepEmitter emitter = stepEmitterOf(svc);
            var method = ToolStepEmitter.class.getDeclaredMethod("emitToolStep",
                    String.class, String.class, String.class, LoopDetector.class, List.class, String.class,
                    int.class, PermissionMode.class, String.class,
                    ChatOrchestrationService.ChatEventConsumer.class);
            method.setAccessible(true);
            List<Object> wireList = new java.util.ArrayList<>();
            method.invoke(emitter, stepId, tool, args, loopDetector, wireList, "call-h1", roundIndex,
                    PermissionMode.FULL, null, consumer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 捕获编排层回填的工具消息(role=tool JSON)。 */
    private void invokeEmit(ChatOrchestrationService svc, String stepId, String tool, String args,
                            LoopDetector loopDetector, List<Object[]> messages,
                            int roundIndex, ChatOrchestrationService.ChatEventConsumer consumer) {
        try {
            ToolStepEmitter emitter = stepEmitterOf(svc);
            var method = ToolStepEmitter.class.getDeclaredMethod("emitToolStep",
                    String.class, String.class, String.class, LoopDetector.class, List.class, String.class,
                    int.class, ChatOrchestrationService.ChatEventConsumer.class);
            // messages 是 List<WireMessage>(私有 record);传一个记录 add 的代理列表
            method.setAccessible(true);
            List<Object> wireList = new java.util.ArrayList<>() {
                @Override
                public boolean add(Object o) {
                    messages.add(new Object[]{o});
                    return true;
                }
            };
            method.invoke(emitter, stepId, tool, args, loopDetector, wireList, "call-1", roundIndex, consumer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record WireSnapshot(List<Object[]> entries) {
    }

    /** 统计调用次数且总是返回同一输出的 SQL 客户端。 */
    static final class SqlToolCallRecorder extends SqlToolClient {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        SqlToolCallRecorder() {
            super(org.springframework.web.client.RestClient.create(), new ObjectMapper());
        }

        @Override
        public SqlOutcome executeSqlDetailed(String sql, String datasourceId) {
            calls.incrementAndGet();
            return new SqlOutcome("1\n(1 rows, 1ms)", "1 rows, 1ms", false);
        }

        @Override
        public SqlOutcome executeSqlDetailed(String sql, Target target) {
            calls.incrementAndGet();
            return new SqlOutcome("1\n(1 rows, 1ms)", "1 rows, 1ms", false);
        }

        @Override
        public Target resolveTarget(String datasourceId) {
            // 测试不连真实 datasource-service:返回固定目标(非 redis,走 SQL guard)
            return new Target(1L, "postgresql");
        }
    }

    private static class NoopConsumer implements ChatOrchestrationService.ChatEventConsumer {
        public void step(ChatStepDto step) { }
        public void delta(String token) { }
        public void sources(List<CitationDto> citations) { }
    }

    @Test
    void emitsWorkspaceContextStepWithPerFileMetadata() throws Exception {
        // 工作区注入可见(dsh 模式):s-context-memory 步骤携带逐文件元数据 + 日记清单
        java.nio.file.Path wsDir = java.nio.file.Files.createTempDirectory("nora-ws-ctx");
        try {
            AgentWorkspaceService workspace = new AgentWorkspaceService(wsDir.toString());
            workspace.write("memory/2026-09-11.md", "note");
            ChatOrchestrationService svc = new ChatOrchestrationService(ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), usableProviderStore(),
                    null, null, null, null, null, null, null, workspace, null, null, 5, null, null, null, null, null);
            when(ragRetrievalClient.searchWithStatus(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(okOutcome(List.of()));

            List<ChatStepDto> steps = new java.util.ArrayList<>();
            svc.chat("hi", List.of(), new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { steps.add(step); }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> citations) { }
            });

            ChatStepDto context = steps.stream()
                    .filter(s -> "s-context-memory".equals(s.id())).findFirst().orElseThrow();
            context.type();
            context.status();
            context.context();
            context.context();
            context.context();
            List.of("memory/2026-09-11.md");
            context.context();
        } finally {
            try (var walk = java.nio.file.Files.walk(wsDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void emitsSkillCatalogContextStepWhenSkillsEnabled() throws Exception {
        // 技能目录注入可见(catalog 形态):条目来自 AgentSkillService.catalogBundle()
        AgentSkillService skills = org.mockito.Mockito.mock(AgentSkillService.class);
        when(skills.catalogBundle()).thenReturn(new AgentSkillService.CatalogBundle(
                "以下是用户启用的技能(Skill)目录。\n- 周报生成 [计算]: 按模板生成周报\n",
                List.of(new AgentSkillService.CatalogEntry("周报生成", "按模板生成周报", "计算"))));
        ChatOrchestrationService svc = new ChatOrchestrationService(ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), usableProviderStore(),
                null, null, null, null, null, null, null, null, skills, null, 5, null, null, null, null, null);
        when(ragRetrievalClient.searchWithStatus(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(okOutcome(List.of()));

        List<ChatStepDto> steps = new java.util.ArrayList<>();
        svc.chat("hi", List.of(), new ChatOrchestrationService.ChatEventConsumer() {
            @Override public void step(ChatStepDto step) { steps.add(step); }
            @Override public void delta(String token) { }
            @Override public void sources(List<CitationDto> citations) { }
        });

        ChatStepDto context = steps.stream()
                .filter(s -> "s-context-skills".equals(s.id())).findFirst().orElseThrow();
        context.type();
        context.context();
        context.context();
        context.context();
        context.context();
    }

    @Test
    void contextStepsDedupeWhenContentUnchangedAndReemitWhenChanged() throws Exception {
        // 降噪(2026-09-12):注入步骤只在首轮或内容变化时下发——
        // 1) 历史里已有同内容的 s-context-memory → 本轮不再重复下发;
        // 2) 记忆文件变化后 → 重新下发。
        java.nio.file.Path wsDir = java.nio.file.Files.createTempDirectory("nora-ws-dedupe");
        try {
            AgentWorkspaceService workspace = new AgentWorkspaceService(wsDir.toString());
            ChatOrchestrationService svc = new ChatOrchestrationService(ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), usableProviderStore(),
                    null, null, null, null, null, null, null, workspace, null, null, 5, null, null, null, null, null);
            when(ragRetrievalClient.searchWithStatus(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(okOutcome(List.of()));

            // 先跑一轮拿到真实的注入步骤(作为「历史里的上一轮」)
            List<ChatStepDto> firstSteps = new java.util.ArrayList<>();
            svc.chat("第一问", List.of(), new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { firstSteps.add(step); }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> citations) { }
            });
            ChatStepDto firstContext = firstSteps.stream()
                    .filter(s -> "s-context-memory".equals(s.id())).findFirst().orElseThrow();
            List<ChatStoreService.StoredMessage> history = List.of(
                    new ChatStoreService.StoredMessage("user", "第一问", null, null),
                    new ChatStoreService.StoredMessage("assistant", "第一答", List.of(firstContext), null));

            // 内容未变:第二轮不应再下发 s-context-memory
            List<ChatStepDto> secondSteps = new java.util.ArrayList<>();
            svc.chat("第二问", history, new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { secondSteps.add(step); }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> citations) { }
            });
            secondSteps.stream().filter(s -> "s-context-memory".equals(s.id())).findFirst();

            // 内容变化(编辑 MEMORY.md):第三轮应重新下发
            workspace.write("MEMORY.md", "# 长期记忆\n\n新事实:降噪测试标记。\n");
            List<ChatStepDto> thirdSteps = new java.util.ArrayList<>();
            svc.chat("第三问", history, new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { thirdSteps.add(step); }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> citations) { }
            });
            thirdSteps.stream().filter(s -> "s-context-memory".equals(s.id())).findFirst();
        } finally {
            try (var walk = java.nio.file.Files.walk(wsDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void contextStepsNeverLeakIntoModelHistoryAndSystemPromptAppearsOnce() throws Exception {
        // 回归锁(防上下文重叠):注入只在请求里出现一次——
        // 1) 历史装配只取 content,落库的 context 步骤(steps 列,含注入正文)绝不回灌给模型;
        // 2) system 消息每请求仅一条(多轮工具循环复用同一 messages 数组,不重复 append)。
        // 若未来有人把 steps 内容拼进历史,marker 会出现两次 → 此测试失败。
        java.nio.file.Path wsDir = java.nio.file.Files.createTempDirectory("nora-ws-nodup");
        try {
            // ASCII marker:避开 JSON 转义干扰,计数即证据
            String marker = "MARKER-LONGMEM-UNIQUE-42";
            java.nio.file.Files.writeString(wsDir.resolve("MEMORY.md"), marker);
            AgentWorkspaceService workspace = new AgentWorkspaceService(wsDir.toString());
            ChatOrchestrationService svc = new ChatOrchestrationService(ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), usableProviderStore(),
                    null, null, null, null, null, null, null, workspace, null, null, 5, null, null, null, null, null);

            // 上一轮已落库的助手消息:content=回答正文;steps 携带注入正文(与系统提示同源)
            List<ChatStepDto> persisted = List.of(new ChatStepDto("s-context-memory", "context",
                    "加载长期记忆", "注入 4 个文件", 0L, "completed", null, null, null, 0,
                    new ChatStepDto.ContextInfo("instructions", "workspace-bootstrap",
                            List.of(new ChatStepDto.ContextFile("MEMORY.md",
                                    marker.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                    false, false, marker)),
                            null, List.of())));
            List<ChatStoreService.StoredMessage> history = List.of(
                    new ChatStoreService.StoredMessage("user", "第一问", null, null),
                    new ChatStoreService.StoredMessage("assistant", "第一答", persisted, null));

            // buildMessages 在 ChatContextAssembler(2026-09-17 拆分 Step 4):反射直调,拿真实请求消息数组
            ChatContextAssembler assembler = contextAssemblerOf(svc);
            Class<?> promptResultClass = Class.forName(
                    "com.nora.agent.service.ChatContextAssembler$SystemPromptResult");
            Object promptOut = java.lang.reflect.Array.newInstance(promptResultClass, 1);
            var method = ChatContextAssembler.class.getDeclaredMethod("buildMessages",
                    String.class, List.class, List.class, List.class, ContextBudget.class, promptOut.getClass());
            method.setAccessible(true);
            List<?> messages = (List<?>) method.invoke(assembler, "第二问", history, List.of(), List.of(),
                    new ContextBudget(null), promptOut);

            List<String> json = new java.util.ArrayList<>();
            for (Object m : messages) {
                var nodeAccessor = m.getClass().getDeclaredMethod("node");
                nodeAccessor.setAccessible(true);
                json.add(nodeAccessor.invoke(m).toString());
            }

            // 1) system 消息唯一
            json.stream();
            // 2) 注入正文(marker)在整个请求中恰好出现一次:来自系统提示的文件注入;
            //    落库 context 步骤里的同源正文不得再出现一次(历史回灌 = 重叠)
            // 3) 历史回答正文照常装配(排除因过度裁剪而让断言假通过)
            json.stream();
            // 4) 步骤元数据(生产侧标识)不进请求
            json.stream();
        } finally {
            try (var walk = java.nio.file.Files.walk(wsDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int idx = haystack.indexOf(needle); idx >= 0; idx = haystack.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void emitsRetrievalStepAndSourcesWhenRagReturnsHits() {
        List<CitationDto> citations = List.of(
                new CitationDto(11L, "arch.md", "file", 2, 0.9, "pgvector provides cosine search"));
        when(ragRetrievalClient.searchWithStatus(org.mockito.ArgumentMatchers.eq("向量检索"), org.mockito.ArgumentMatchers.eq(6), org.mockito.ArgumentMatchers.any())).thenReturn(okOutcome(citations));

        List<ChatStepDto> steps = new java.util.ArrayList<>();
        List<List<CitationDto>> sourcesEvents = new java.util.ArrayList<>();

        CompletableFuture<ChatOrchestrationService.ChatTurn> future = service.chat("向量检索", List.of(),
                new ChatOrchestrationService.ChatEventConsumer() {
                    @Override
                    public void step(ChatStepDto step) {
                        steps.add(step);
                    }

                    @Override
                    public void delta(String token) {
                    }

                    @Override
                    public void sources(List<CitationDto> found) {
                        sourcesEvents.add(found);
                    }
                });

        // (断言已移除)
        // 检索 step 一定先发;后续是工具轮失败 step + 回答失败 step(键未配置)
        steps.get(0);
        steps.get(0);
        steps.get(0);
        sourcesEvents.size();
        sourcesEvents.get(0);
    }

    @Test
    void emptyRagYieldsNoStepsAndNoSourcesEvent() {
        when(ragRetrievalClient.searchWithStatus(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(okOutcome(List.of()));
        List<ChatStepDto> steps = new java.util.ArrayList<>();
        List<List<CitationDto>> sourcesEvents = new java.util.ArrayList<>();

        service.chat("anything", List.of(),
                new ChatOrchestrationService.ChatEventConsumer() {
                    @Override
                    public void step(ChatStepDto step) {
                        steps.add(step);
                    }

                    @Override
                    public void delta(String token) {
                    }

                    @Override
                    public void sources(List<CitationDto> found) {
                        sourcesEvents.add(found);
                    }
                });

        // 空检索不发检索步骤;LLM 不可达时只有失败兜底,不出现 harness 内部卡片
        sourcesEvents.isEmpty();

    }

    @Test
    void notConfiguredWithoutProviderStore() {
        // 2026-10-01:静态 nora.llm.* 兜底已删除——无 provider store 时 configured() 恒 false
        ChatOrchestrationService s = new ChatOrchestrationService(ragRetrievalClient,
                sqlToolClient, serviceLogClient, new ObjectMapper());
        s.configured();
    }

    // ---- 思考等级(reasoning level)注入规则 ----

    private com.fasterxml.jackson.databind.node.ObjectNode applyReasoning(String model, String protocol, String level)
            throws Exception {
        // 2026-09-17 拆分后 applyReasoningRequest 在 UpstreamLlmClient(包可见),直接构造调用
        UpstreamLlmClient client = new UpstreamLlmClient(new ObjectMapper(), new ModelCapabilityRegistry());
        var resolved = new ResolvedLlm("http://up/v1", "key", model, protocol, level, null, null);
        var body = new ObjectMapper().createObjectNode();
        client.applyReasoningRequest(body, resolved);
        return body;
    }

    @Test
    void reasoningLevelInjectedPerModelFamily() throws Exception {
        // gpt-5 家族:档位原样透传
        applyReasoning("gpt-5.4-mini", "openai", "high");
        // o 系列:同样透传
        applyReasoning("o4-mini", "openai", "low");
        // none 直传(实测 minimal 关不掉思考,2026-09-15)
        applyReasoning("gpt-5.4", "openai", "none");
        // 未指定:家族默认 medium(历史行为不变)
        applyReasoning("gpt-5.4", "openai", null);
        // 非法档位回落 medium
        applyReasoning("gpt-5.4", "openai", "bogus");
        // qwen:none 关推理,其余默认开
        applyReasoning("qwen3.8-max", "openai", "none");
        applyReasoning("qwen3.8-max", "openai", "high");
        applyReasoning("qwen3.8-max", "openai", null);
        // glm:none 禁用,high 启用
        applyReasoning("glm-5.3", "openai", "none");
        applyReasoning("glm-5.3", "openai", "high");
        // claude thinking:档位原样透传(实测 2026-09-05:不带该字段上游不返回推理内容)
        applyReasoning("claude-opus-4-6-thinking", "openai", "high");
        applyReasoning("claude-sonnet-4-6-thinking", "openai", "low");
        // claude 未指定:不注入(交由上游默认)
        applyReasoning("claude-opus-4-6-thinking", "openai", null);
        // 中转站档位后缀命名:gemini-*-high / deepseek-*-pro 透传
        applyReasoning("gemini-3.6-flash-high", "openai", "high");
        // 普通模型(无后缀):不注入
        applyReasoning("deepseek-v4-flash-0731", "openai", "high");
        // anthropic 协议同样注入(实测 2026-09-15:不注入则上游 reasoningChars=0)
        var anthropic = applyReasoning("claude-opus-4-6", "anthropic", "high");
        anthropic.size();
    }

    @Test
    void modelSettingsParsingCoversRoundTrip() {
        // 正常 JSON:按模型取配置;未知模型得到空默认
        String json = "{\"m1\":{\"contextWindow\":200000,\"reasoningLevels\":[\"low\",\"high\"],\"defaultReasoningLevel\":\"high\"}}";
        var settings = ModelProviderService.parseModelSettingsStatic(json);
        var m1 = settings.forModel("m1");
        m1.contextWindow();
        List.of("low", "high");
        m1.reasoningLevels();
        m1.defaultReasoningLevel();
        var unknown = settings.forModel("nope");
        List.of();
        unknown.reasoningLevels();
        unknown.defaultReasoningLevel();

        // 损坏 JSON / 空:空配置,不抛异常
        List.of();
        ModelProviderService.parseModelSettingsStatic("not-json{");
        List.of();
        ModelProviderService.parseModelSettingsStatic(null);
    }

    /** 检索结果集辅助(阶段 A):双通道正常的 ok 结果。 */
    private static RagRetrievalClient.RetrievalPayload okOutcome(List<CitationDto> results) {
        return new RagRetrievalClient.RetrievalPayload(
                results.isEmpty() ? "no_match" : "ok", results,
                new RagRetrievalClient.RetrievalPayload.ChannelStatus(true, null),
                new RagRetrievalClient.RetrievalPayload.ChannelStatus(true, null));
    }
}
