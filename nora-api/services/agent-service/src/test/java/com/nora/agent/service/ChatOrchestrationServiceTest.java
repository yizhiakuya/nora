package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.config.LlmProperties;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        // No API key: the service must still run retrieval steps;
        // the LLM call itself fails downstream (wire client hits a bad URL).
        LlmProperties properties = new LlmProperties("test-key", "http://localhost:9/v1", "test-model");
        service = new ChatOrchestrationService(properties, ragRetrievalClient,
                sqlToolClient, serviceLogClient, new ObjectMapper());
    }

    @Test
    void rejectsBlankAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> service.chat(" ", List.of(), List.of(), new NoopConsumer()));
        assertThrows(IllegalArgumentException.class, () -> service.chat("x".repeat(8001), List.of(), List.of(), new NoopConsumer()));
    }

    @Test
    void sqlGuardrailRejectsWritesAndMultipleStatements() {
        assertTrue(ChatOrchestrationService.guardSql("UPDATE users SET x=1") != null);
        assertTrue(ChatOrchestrationService.guardSql("SELECT 1; DELETE FROM users") != null);
        assertEquals(null, ChatOrchestrationService.guardSql("SELECT 1;"));
    }

    @Test
    void riskClassifierTiersNewManageTools() {
        // 数据源:list 低风险;create/test HIGH;remove CRITICAL(不可逆,任何档位问)
        assertEquals(RiskClassifier.Risk.LOW,
                RiskClassifier.classify("manage_datasource", "{\"action\": \"list\"}"));
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_datasource", "{\"action\": \"create\", \"engine\": \"postgresql\"}"));
        assertEquals(RiskClassifier.Risk.CRITICAL,
                RiskClassifier.classify("manage_datasource", "{\"action\": \"remove\", \"target\": \"old-db\"}"));
        // 纳管源:register/remove CRITICAL(PROC 注册=宿主机命令纳入守护);enable/disable HIGH
        assertEquals(RiskClassifier.Risk.CRITICAL,
                RiskClassifier.classify("manage_service", "{\"action\": \"register\", \"kind\": \"PROC\", \"command\": \"x\"}"));
        assertEquals(RiskClassifier.Risk.CRITICAL,
                RiskClassifier.classify("manage_service", "{\"action\": \"remove\", \"target\": \"3\"}"));
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_service", "{\"action\": \"disable\", \"target\": \"backup\"}"));
        assertEquals(RiskClassifier.Risk.LOW,
                RiskClassifier.classify("manage_service", "{\"action\": \"list\"}"));
        // action 缺失按未知处理:不低于 HIGH
        assertEquals(RiskClassifier.Risk.HIGH, RiskClassifier.classify("manage_datasource", "{}"));
        // 原有工具的档位不变
        assertEquals(RiskClassifier.Risk.HIGH, RiskClassifier.classify("execute_write_sql", "{}"));
        assertEquals(RiskClassifier.Risk.LOW, RiskClassifier.classify("execute_sql", "{}"));
    }

    @Test
    void datasourceAndServiceValidatorsGiveActionableErrors() {
        // engine 白名单与必填项
        assertTrue(RiskClassifier.validateDatasourceCreate("oracle", "h", 1521, "db") != null);
        assertTrue(RiskClassifier.validateDatasourceCreate("postgresql", null, 5432, "db") != null);
        assertTrue(RiskClassifier.validateDatasourceCreate("postgresql", "h", 70000, "db") != null);
        assertEquals(null, RiskClassifier.validateDatasourceCreate("PostgreSQL", "h", 5432, "db"));
        // kind 与字段匹配(env-service 同语义)
        assertTrue(RiskClassifier.validateServiceRegister("PROC", null, null, null) != null);
        assertTrue(RiskClassifier.validateServiceRegister("DOCKER", null, null, "cmd") != null);
        assertEquals(null, RiskClassifier.validateServiceRegister("proc", null, null, "/opt/run.sh"));
        assertEquals(null, RiskClassifier.validateServiceRegister("docker", null, "nora-redis", null));
        // action 白名单
        assertTrue(RiskClassifier.validateDatasourceAction("drop") != null);
        assertTrue(RiskClassifier.validateServiceAction("restart") != null);
    }

    @Test
    void guardrailErrorsFollowThreePartShape() {
        // 三段式:拒绝什么 + 违反哪条 + 正确示例(错误即提示,引导模型自纠)
        String write = ChatOrchestrationService.guardSql("DELETE FROM users");
        assertTrue(write.contains("拒绝执行"));
        assertTrue(write.contains("SELECT"));
        assertTrue(write.contains("示例"));
        String missing = ChatOrchestrationService.guardSql(" ");
        assertTrue(missing.contains("缺少 sql 参数"));
        assertTrue(missing.contains("示例"));
        String multi = ChatOrchestrationService.guardSql("SELECT 1; SELECT 2");
        assertTrue(multi.contains("一次只允许一条"));
    }

    @Test
    void persistedStepsMergeDedupsAndDropsDanglingRunning() {
        List<ChatStepDto> raw = List.of(
                new ChatStepDto("s1", "tool", "决策", null, null, "running"),
                new ChatStepDto("s1", "tool", "决策", "完成", 100L, "completed"),
                new ChatStepDto("s1-call", "tool", "工具", null, null, "running"),
                new ChatStepDto("s2", "tool", "决策", "完成", 50L, "completed"));
        List<ChatStepDto> merged = ChatStoreService.mergeSteps(raw);
        assertEquals(2, merged.size(), "running rows collapse into their terminal state; dangling running dropped");
        assertEquals("completed", merged.get(0).status());
        assertEquals("s2", merged.get(1).id());
    }

    @Test
    void toolResultBudgetFollowsHarnessNumbers() throws Exception {
        // 成功输出 30K 上限、失败输出 10K 头尾摘录,截断都要有标记
        var successBound = invokeBounded("x".repeat(40_000));
        assertTrue(successBound.content().length() < 40_000, "success result is cut to budget");
        assertTrue(successBound.content().contains("已截断"));
        var failureBound = invokeBounded("ERROR: " + "head".repeat(2_000) + "mid" + "tail".repeat(2_000));
        assertTrue(failureBound.content().length() <= ChatOrchestrationService.MAX_FAILURE_CHARS + 100,
                "failure excerpt within budget");
        assertTrue(failureBound.content().contains("中间省略"), "failure excerpt keeps head+tail");
        assertTrue(failureBound.content().endsWith("tailtail"), "failure excerpt keeps the tail");
        var small = invokeBounded("col1\tcol2\n(3 rows, 12ms)");
        assertEquals("3 rows, 12ms", small.summary(), "row summary derived from TSV footer");
    }

    private ChatOrchestrationService.ToolOutcome invokeBounded(String input) throws Exception {
        var method = ChatOrchestrationService.class.getDeclaredMethod("bounded", String.class, String.class);
        method.setAccessible(true);
        return (ChatOrchestrationService.ToolOutcome) method.invoke(service, input, null);
    }

    @Test
    void repeatedIdenticalCallsTripLoopBreaker() {
        // 同参数第 4 次调用:不再执行工具,直接 declined + 错误回填,模型可自纠
        SqlToolCallRecorder recorder = new SqlToolCallRecorder();
        ChatOrchestrationService loopService = new ChatOrchestrationService(
                new LlmProperties("test-key", "http://localhost:9/v1", "test-model"),
                ragRetrievalClient, recorder, serviceLogClient, new ObjectMapper(), 5);
        Map<String, Integer> fingerprints = new java.util.HashMap<>();
        List<ChatStepDto> steps = new java.util.ArrayList<>();
        List<WireSnapshot> wire = new java.util.ArrayList<>();
        ChatOrchestrationService.ChatEventConsumer consumer = new NoopConsumer() {
            @Override
            public void step(ChatStepDto step) { steps.add(step); }
        };

        for (int i = 0; i < 4; i++) {
            List<Object[]> captured = new java.util.ArrayList<>();
            invokeEmit(loopService, "s-call-" + i, "execute_sql", "{\"sql\": \"SELECT 1\"}",
                    fingerprints, captured, i + 1, consumer);
            // emitToolStep appends the tool message onto our captured list via backfill
            wire.add(new WireSnapshot(captured));
        }

        long declined = steps.stream().filter(s -> "declined".equals(s.status())).count();
        assertEquals(1, declined, "exactly the 4th identical call is declined");
        assertEquals(3, recorder.calls.get(), "tool only executed 3 times before the breaker");
        ChatStepDto declinedStep = steps.stream().filter(s -> "declined".equals(s.status())).findFirst().orElseThrow();
        assertEquals("declined", declinedStep.result().error() != null ? "declined" : "missing", "declined carries error text");
        assertTrue(declinedStep.result().error().contains("重复调用已阻断"));
        assertEquals(4, declinedStep.roundIndex(), "structured steps carry the ReAct round");
    }

    @Test
    void headlessChannelRejectsCriticalTools() {
        // 无会话通道(/agent/run):CRITICAL 工具必须被拒——没有用户在场,审批不可达;
        // step 记 declined,模型收到引导文案,且工具从未执行(下方无 recorder 涉及)
        ChatOrchestrationService headless = new ChatOrchestrationService(
                new LlmProperties("test-key", "http://localhost:9/v1", "test-model"),
                ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), 5);
        Map<String, Integer> fingerprints = new java.util.HashMap<>();
        List<ChatStepDto> steps = new java.util.ArrayList<>();
        ChatOrchestrationService.ChatEventConsumer consumer = new NoopConsumer() {
            @Override
            public void step(ChatStepDto step) { steps.add(step); }
        };

        invokeEmitFull(headless, "s-h1", "manage_datasource",
                "{\"action\": \"remove\", \"target\": \"1\"}", fingerprints, 1, consumer);

        assertEquals("declined", steps.get(steps.size() - 1).status(), "critical tool declined on headless channel");
        assertTrue(steps.get(steps.size() - 1).result().error().contains("不可逆操作"),
                "error text tells the model why + what to do");
    }

    /** emitToolStep with explicit permission mode and sessionId (headless = null session). */
    private void invokeEmitFull(ChatOrchestrationService svc, String stepId, String tool, String args,
                                Map<String, Integer> fingerprints,
                                int roundIndex, ChatOrchestrationService.ChatEventConsumer consumer) {
        try {
            var method = ChatOrchestrationService.class.getDeclaredMethod("emitToolStep",
                    String.class, String.class, String.class, Map.class, List.class, String.class,
                    int.class, PermissionMode.class, String.class,
                    ChatOrchestrationService.ChatEventConsumer.class);
            method.setAccessible(true);
            List<Object> wireList = new java.util.ArrayList<>();
            method.invoke(svc, stepId, tool, args, fingerprints, wireList, "call-h1", roundIndex,
                    PermissionMode.FULL, null, consumer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Captures the tool message the orchestrator backfills (role=tool JSON). */
    private void invokeEmit(ChatOrchestrationService svc, String stepId, String tool, String args,
                            Map<String, Integer> fingerprints, List<Object[]> messages,
                            int roundIndex, ChatOrchestrationService.ChatEventConsumer consumer) {
        try {
            var method = ChatOrchestrationService.class.getDeclaredMethod("emitToolStep",
                    String.class, String.class, String.class, Map.class, List.class, String.class,
                    int.class, ChatOrchestrationService.ChatEventConsumer.class);
            // messages is List<WireMessage> (private record); pass a proxy list that records adds
            method.setAccessible(true);
            List<Object> wireList = new java.util.ArrayList<>() {
                @Override
                public boolean add(Object o) {
                    messages.add(new Object[]{o});
                    return true;
                }
            };
            method.invoke(svc, stepId, tool, args, fingerprints, wireList, "call-1", roundIndex, consumer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record WireSnapshot(List<Object[]> entries) {
    }

    /** SQL client that counts invocations and always returns the same output. */
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
            ChatOrchestrationService svc = new ChatOrchestrationService(
                    new LlmProperties("test-key", "http://localhost:9/v1", "test-model"),
                    ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), null,
                    null, null, null, null, null, null, null, workspace, null, 5, null);
            when(ragRetrievalClient.search(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

            List<ChatStepDto> steps = new java.util.ArrayList<>();
            svc.chat("hi", List.of(), new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { steps.add(step); }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> citations) { }
            });

            ChatStepDto context = steps.stream()
                    .filter(s -> "s-context-memory".equals(s.id())).findFirst().orElseThrow();
            assertEquals("context", context.type());
            assertEquals("completed", context.status());
            assertEquals("instructions", context.context().form());
            assertEquals("workspace-bootstrap", context.context().kind());
            assertEquals(4, context.context().files().size(), "SOUL/AGENTS/USER/MEMORY");
            assertEquals(List.of("memory/2026-09-11.md"), context.context().dailyNotes());
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
        ChatOrchestrationService svc = new ChatOrchestrationService(
                new LlmProperties("test-key", "http://localhost:9/v1", "test-model"),
                ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), null,
                null, null, null, null, null, null, null, null, skills, 5, null);
        when(ragRetrievalClient.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        List<ChatStepDto> steps = new java.util.ArrayList<>();
        svc.chat("hi", List.of(), new ChatOrchestrationService.ChatEventConsumer() {
            @Override public void step(ChatStepDto step) { steps.add(step); }
            @Override public void delta(String token) { }
            @Override public void sources(List<CitationDto> citations) { }
        });

        ChatStepDto context = steps.stream()
                .filter(s -> "s-context-skills".equals(s.id())).findFirst().orElseThrow();
        assertEquals("context", context.type());
        assertEquals("catalog", context.context().form());
        assertEquals("skill-catalog", context.context().kind());
        assertEquals(1, context.context().entries().size());
        assertEquals("周报生成", context.context().entries().get(0).name());
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
            ChatOrchestrationService svc = new ChatOrchestrationService(
                    new LlmProperties("test-key", "http://localhost:9/v1", "test-model"),
                    ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper(), null,
                    null, null, null, null, null, null, null, workspace, null, 5, null);

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

            // buildMessages 是私有装配方法:反射直调,拿真实请求消息数组
            Class<?> promptResultClass = Class.forName(
                    "com.nora.agent.service.ChatOrchestrationService$SystemPromptResult");
            Object promptOut = java.lang.reflect.Array.newInstance(promptResultClass, 1);
            var method = ChatOrchestrationService.class.getDeclaredMethod("buildMessages",
                    String.class, List.class, List.class, List.class, ContextBudget.class, promptOut.getClass());
            method.setAccessible(true);
            List<?> messages = (List<?>) method.invoke(svc, "第二问", history, List.of(), List.of(),
                    new ContextBudget(null), promptOut);

            List<String> json = new java.util.ArrayList<>();
            for (Object m : messages) {
                var nodeAccessor = m.getClass().getDeclaredMethod("node");
                nodeAccessor.setAccessible(true);
                json.add(nodeAccessor.invoke(m).toString());
            }

            // 1) system 消息唯一
            assertEquals(1, json.stream().filter(j -> j.contains("\"role\":\"system\"")).count(),
                    "每请求仅一条 system 消息(工具轮复用同一数组,不重复注入)");
            // 2) 注入正文(marker)在整个请求中恰好出现一次:来自系统提示的文件注入;
            //    落库 context 步骤里的同源正文不得再出现一次(历史回灌 = 重叠)
            int markerHits = json.stream().mapToInt(j -> countOccurrences(j, marker)).sum();
            assertEquals(1, markerHits,
                    "注入正文在请求中恰好一次:context 步骤载荷绝不回灌历史(否则上下文重叠)");
            // 3) 历史回答正文照常装配(排除因过度裁剪而让断言假通过)
            assertTrue(json.stream().anyMatch(j -> j.contains("第一答")), "历史 assistant content 正常装配");
            // 4) 步骤元数据(生产侧标识)不进请求
            assertTrue(json.stream().noneMatch(j -> j.contains("workspace-bootstrap")),
                    "context 步骤元数据绝不进请求");
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
        when(ragRetrievalClient.search("向量检索", 6)).thenReturn(citations);

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

        assertTrue(future != null, "chat returns a future");
        // 检索 step 一定先发;后续是工具轮失败 step + 回答失败 step(键未配置)
        assertEquals("tool", steps.get(0).type());
        assertEquals("检索知识库", steps.get(0).title());
        assertEquals("completed", steps.get(0).status());
        assertEquals(1, sourcesEvents.size());
        assertEquals("arch.md", sourcesEvents.get(0).get(0).docName());
    }

    @Test
    void emptyRagYieldsNoStepsAndNoSourcesEvent() {
        when(ragRetrievalClient.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
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
        assertTrue(sourcesEvents.isEmpty(), "no sources event without hits");
        steps.forEach(s ->
                org.junit.jupiter.api.Assertions.assertNotEquals("模型决策", s.title(), "no harness-internal decision cards"));
    }

    @Test
    void notConfiguredFlagMirrorsProperties() {
        LlmProperties unconfigured = new LlmProperties("", "http://localhost:9/v1", "m");
        ChatOrchestrationService s = new ChatOrchestrationService(unconfigured, ragRetrievalClient,
                sqlToolClient, serviceLogClient, new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertFalse(s.configured());

        LlmProperties configured = new LlmProperties("key", "http://localhost:9/v1", "m");
        ChatOrchestrationService s2 = new ChatOrchestrationService(configured, ragRetrievalClient,
                sqlToolClient, serviceLogClient, new ObjectMapper());
        assertTrue(s2.configured());
    }

    // ---- 思考等级(reasoning level)注入规则 ----

    /** Builds the service whose private applyReasoningRequest is under test. */
    private ChatOrchestrationService reasoningService() {
        return new ChatOrchestrationService(
                new LlmProperties("key", "http://localhost:9/v1", "test-model"),
                ragRetrievalClient, sqlToolClient, serviceLogClient, new ObjectMapper());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode applyReasoning(String model, String protocol, String level)
            throws Exception {
        ChatOrchestrationService svc = reasoningService();
        var resolved = new ChatOrchestrationService.ResolvedLlm("http://up/v1", "key", model, protocol, level, null);
        var method = ChatOrchestrationService.class.getDeclaredMethod("applyReasoningRequest",
                com.fasterxml.jackson.databind.node.ObjectNode.class, ChatOrchestrationService.ResolvedLlm.class);
        method.setAccessible(true);
        var body = new ObjectMapper().createObjectNode();
        method.invoke(svc, body, resolved);
        return body;
    }

    @Test
    void reasoningLevelInjectedPerModelFamily() throws Exception {
        // gpt-5 家族:档位原样透传
        assertEquals("high", applyReasoning("gpt-5.4-mini", "openai", "high").get("reasoning_effort").asText());
        // o 系列:同样透传
        assertEquals("low", applyReasoning("o4-mini", "openai", "low").get("reasoning_effort").asText());
        // none 在 OpenAI 通道映射为 minimal
        assertEquals("minimal", applyReasoning("gpt-5.4", "openai", "none").get("reasoning_effort").asText());
        // 未指定:家族默认 medium(历史行为不变)
        assertEquals("medium", applyReasoning("gpt-5.4", "openai", null).get("reasoning_effort").asText());
        // 非法档位回落 medium
        assertEquals("medium", applyReasoning("gpt-5.4", "openai", "bogus").get("reasoning_effort").asText());
        // qwen:none 关推理,其余默认开
        assertEquals(false, applyReasoning("qwen3.8-max", "openai", "none").get("enable_thinking").asBoolean());
        assertEquals(true, applyReasoning("qwen3.8-max", "openai", "high").get("enable_thinking").asBoolean());
        assertEquals(true, applyReasoning("qwen3.8-max", "openai", null).get("enable_thinking").asBoolean());
        // glm:none disabled,high enabled
        assertEquals("disabled", applyReasoning("glm-5.3", "openai", "none").path("thinking").path("type").asText());
        assertEquals("enabled", applyReasoning("glm-5.3", "openai", "high").path("thinking").path("type").asText());
        // claude thinking:档位原样透传(实测 2026-09-05:不带该字段上游不返回推理内容)
        assertEquals("high", applyReasoning("claude-opus-4-6-thinking", "openai", "high").get("reasoning_effort").asText());
        assertEquals("low", applyReasoning("claude-sonnet-4-6-thinking", "openai", "low").get("reasoning_effort").asText());
        // claude 未指定:不注入(交由上游默认)
        assertEquals(null, applyReasoning("claude-opus-4-6-thinking", "openai", null).get("reasoning_effort"));
        // 中转站档位后缀命名:gemini-*-high / deepseek-*-pro 透传
        assertEquals("high", applyReasoning("gemini-3.6-flash-high", "openai", "high").get("reasoning_effort").asText());
        // 普通模型(无后缀):不注入
        assertEquals(null, applyReasoning("deepseek-v4-flash-0731", "openai", "high").get("reasoning_effort"));
        // anthropic 协议不做 OpenAI 侧注入
        var anthropic = applyReasoning("claude-opus-4-6", "anthropic", "high");
        assertEquals(0, anthropic.size(), "anthropic protocol gets no openai reasoning fields");
    }

    @Test
    void modelSettingsParsingCoversRoundTrip() {
        // 正常 JSON:按模型取配置;未知模型得到空默认
        String json = "{\"m1\":{\"contextWindow\":200000,\"reasoningLevels\":[\"low\",\"high\"],\"defaultReasoningLevel\":\"high\"}}";
        var settings = ModelProviderService.parseModelSettingsStatic(json);
        var m1 = settings.forModel("m1");
        assertEquals(200000L, m1.contextWindow());
        assertEquals(List.of("low", "high"), m1.reasoningLevels());
        assertEquals("high", m1.defaultReasoningLevel());
        var unknown = settings.forModel("nope");
        assertEquals(List.of(), unknown.reasoningLevels());
        assertEquals(null, unknown.defaultReasoningLevel());

        // 损坏 JSON / 空:空配置,不抛异常
        assertEquals(List.of(), ModelProviderService.parseModelSettingsStatic("not-json{").forModel("m").reasoningLevels());
        assertEquals(List.of(), ModelProviderService.parseModelSettingsStatic(null).forModel("m").reasoningLevels());
    }
}
