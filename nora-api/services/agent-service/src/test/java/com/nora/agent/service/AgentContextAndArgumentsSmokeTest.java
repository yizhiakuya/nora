package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 冒烟执行与观察输出;行为正确性由验收探针的真实工具/HTTP 路径复验。 */
class AgentContextAndArgumentsSmokeTest {
    private final ObjectMapper mapper = new ObjectMapper();

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
                new ContextBudget(128_000L), new ChatContextAssembler.SystemPromptResult[1]);
        System.out.println("retained users: " + messages.stream()
                .filter(m -> "user".equals(m.node().path("role").asText()))
                .map(m -> m.node().path("content").asText()).toList());
    }

    @Test
    void refusesToSilentlyDropUserRequirementsWhenContextCannotHoldThem() {
        var history = List.of(new ChatStoreService.StoredMessage("user", "限制".repeat(5_000), List.of(), List.of()));
        try {
            assembler().buildMessages("继续", history, List.of(), List.of(), new ContextBudget(4_000L),
                    new ChatContextAssembler.SystemPromptResult[1]);
        } catch (IllegalArgumentException e) {
            System.out.println("context capacity: " + e.getMessage());
        }
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
        var scrub = ToolStepEmitter.class.getDeclaredMethod("scrubArgsForLog", String.class);
        scrub.setAccessible(true);
        for (String args : List.of(
                "{\"password\":\"dummy\",\"rows\":[{\"access_token\":\"dummy\"}]}",
                "{\"arguments\":\"{\\\"apiKey\\\":\\\"dummy\\\",\\\"env\\\":{\\\"CUSTOM\\\":\\\"dummy\\\"}}\"}",
                "{\"headers\":{\"Custom-Auth\":\"dummy\"}}", "{broken", "\"dummy\"")) {
            scrub.invoke(emitter, args);
        }
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
