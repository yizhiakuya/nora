package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 冒烟执行与观察输出;行为正确性由验收探针的真实工具/HTTP 路径复验。 */
class AgentContextAndArgumentsSmokeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir java.nio.file.Path workspace;

    private ChatContextAssembler assembler() {
        return new ChatContextAssembler(mapper, null, null, new ChatToolsSpec(mapper, null, null, null, null));
    }

    @Test
    void retainsOrderedUserRequirementsAcrossHistoryTrimming() {
        var history = new ArrayList<ChatStoreService.StoredMessage>();
        for (String text : List.of("统计报告，只读，不删除", "先处理 2026 目录，其余要求不变", "继续吧")) {
            history.add(new ChatStoreService.StoredMessage("user", text, List.of(), List.of()));
        }
        for (int i = 0; i < 42; i++) {
            history.add(new ChatStoreService.StoredMessage(i % 2 == 0 ? "assistant" : "user",
                    "continue " + i, List.of(), List.of()));
        }
        history.add(new ChatStoreService.StoredMessage("user", "改为统计 2025 目录", List.of(), List.of()));
        var messages = assembler().buildMessages("改为统计 2025 目录", history, List.of(), List.of(),
                new ChatContextAssembler.SystemPromptResult[1]);
        System.out.println("retained users: " + messages.stream()
                .filter(m -> "user".equals(m.node().path("role").asText()))
                .map(m -> m.node().path("content").asText()).toList());
    }

    @Test
    void refusesToSilentlyDropUserRequirementsWhenContextCannotHoldThem() {
        var history = List.of(new ChatStoreService.StoredMessage("user", "限制".repeat(5_000), List.of(), List.of()));
        var context = assembler();
        var messages = context.buildMessages("继续", history, List.of(), List.of(),
                new ChatContextAssembler.SystemPromptResult[1]);
        context.compactForRound(messages, new ContextBudget(4_000L), false);
    }

    @Test
    void archivesOmittedHistoryWithoutDiscardingUserConstraints() {
        var ws = new AgentWorkspaceService(workspace.toString());
        var context = new ChatContextAssembler(mapper, ws, null, new ChatToolsSpec(mapper, null, null, null, null));
        var history = new ArrayList<ChatStoreService.StoredMessage>();
        history.add(new ChatStoreService.StoredMessage("user", "只读，不删除文件", List.of(), List.of()));
        history.add(new ChatStoreService.StoredMessage("assistant", "已完成阶段的结论 PROJECT_CODE=demo", List.of(), List.of()));
        for (int i = 0; i < 45; i++) {
            history.add(new ChatStoreService.StoredMessage("assistant", "容量填充，无新操作。".repeat(300), List.of(), List.of()));
        }
        var messages = context.buildMessages("继续", history, List.of(), List.of(),
                new ChatContextAssembler.SystemPromptResult[1]);
        context.compactForRound(messages, new ContextBudget(32_000L), false);
    }

    @Test
    void budgetsSdkThinkingAndArchivesWholeOldToolRounds() {
        var context = new ChatContextAssembler(mapper, new AgentWorkspaceService(workspace.toString()), null,
                new ChatToolsSpec(mapper, null, null, null, null));
        var messages = new ArrayList<WireMessage>();
        messages.add(WireMessage.system(mapper, "system"));
        messages.add(WireMessage.user(mapper, "只读，不重复执行"));
        for (int i = 0; i < 12; i++) {
            var call = mapper.createObjectNode().put("role", "assistant").put("content", "已核实的结论 " + i);
            call.putPOJO(UpstreamLlmClient.SDK_MESSAGE, dev.langchain4j.data.message.AiMessage.builder()
                    .text("已核实的结论 " + i).thinking("思考记录".repeat(2_000)).build());
            call.putArray("tool_calls").addObject().put("id", "call-" + i).putObject("function")
                    .put("name", "manage_workspace").put("arguments", "{\"action\":\"read\"}");
            messages.add(new WireMessage(call));
            messages.add(new WireMessage(mapper.createObjectNode().put("role", "tool").put("tool_call_id", "call-" + i)
                    .put("content", "实际工具证据 " + i)));
        }
        context.requestTokens(messages);
        context.compactForRound(messages, new ContextBudget(32_000L), false);
        context.compactForRound(messages, new ContextBudget(32_000L), true);
        var output = new StringBuilder();
        for (var message : messages) output.append(message.node().path("role").asText()).append('\n');
        System.out.println(output);
    }

    @Test
    void archivesToolTextBeforePressureCompaction() {
        var ws = new AgentWorkspaceService(workspace.toString());
        var context = new ChatContextAssembler(mapper, ws, null, new ChatToolsSpec(mapper, null, null, null, null));
        var messages = new ArrayList<WireMessage>();
        messages.add(WireMessage.system(mapper, "test"));
        for (int i = 0; i < 8; i++) {
            var call = mapper.createObjectNode().put("role", "assistant").put("content", "");
            call.putArray("tool_calls").addObject().put("id", "call-" + i).putObject("function")
                    .put("name", "run_command").put("arguments", "{}");
            messages.add(new WireMessage(call));
            messages.add(new WireMessage(mapper.createObjectNode().put("role", "tool").put("tool_call_id", "call-" + i)
                    .put("content", "ERROR: " + "evidence\n".repeat(2000))));
        }
        context.compactForRound(messages, new ContextBudget(8_000L), false);
        context.compactForRound(messages, new ContextBudget(8_000L), true);
    }

    @Test
    void canonicalizesAcceptedArgumentForms() throws Exception {
        var emitter = new ToolStepEmitter(mapper, null, null, null);
        var canonical = ToolStepEmitter.class.getDeclaredMethod("canonicalArgs", String.class, String.class);
        canonical.setAccessible(true);
        for (String args : List.of(
                "{\"action\":\"read\",\"path\":null,\"filename\":\" \",\"file\":\"a.txt\"}",
                "{\"action\":\"read\",\"path\":\"a.txt\"}",
                "{\"action\":\"read\",\"path\":\"\",\"filename\":\"\",\"file\":\"b.txt\"}")) {
            System.out.println("workspace fingerprint: " + canonical.invoke(emitter, "manage_workspace", args));
        }
        for (String args : List.of(
                "{\"action\":\"call\",\"arguments\":{\"b\":2,\"a\":1}}",
                "{\"action\":\"call\",\"arguments\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}",
                "{\"action\":\"call\"}",
                "{\"action\":\"call\",\"arguments\":\"  \"}")) {
            System.out.println("MCP fingerprint: " + canonical.invoke(emitter, "manage_mcp", args));
        }
    }

    @Test
    void scrubsNestedAndEncodedToolCredentials() throws Exception {
        var emitter = new ToolStepEmitter(mapper, null, null, null);
        var scrub = ToolStepEmitter.class.getDeclaredMethod("scrubArgsForLog", String.class, String.class);
        scrub.setAccessible(true);
        for (String args : List.of(
                "{\"password\":\"dummy\",\"rows\":[{\"access_token\":\"dummy\"}]}",
                "{\"arguments\":\"{\\\"apiKey\\\":\\\"dummy\\\",\\\"env\\\":{\\\"CUSTOM\\\":\\\"dummy\\\"}}\"}",
                "{\"headers\":{\"Custom-Auth\":\"dummy\"}}", "{broken", "\"dummy\"")) {
            scrub.invoke(emitter, args, null);
        }
        scrub.invoke(emitter, "{\"action\":\"set\",\"key\":\"GH_TOKEN\",\"value\":\"dummy\",\"secret\":true,\"description\":\"dummy\",\"note\":\"dummy\"}", "manage_env_vars");
    }

    @Test
    void parsesMcpObjectsAndRejectsInvalidShapes() throws Exception {
        for (String json : List.of("{}", "null", "\"{}\"", "\"  \"", "[]", "\"[]\"", "\"{broken\"")) {
            try {
                System.out.println("MCP parsed: " + McpManagementTools.mcpArguments(mapper, mapper.readTree(json)));
            } catch (IllegalArgumentException e) {
                System.out.println("MCP rejected: " + e.getMessage());
            }
        }
    }
}
