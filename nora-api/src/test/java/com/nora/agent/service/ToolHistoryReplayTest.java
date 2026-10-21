package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;

class ToolHistoryReplayTest {
    @Test
    void preservesEndedRunStatusWithAndWithoutToolHistory() {
        var mapper = new ObjectMapper();
        var assembler = new ChatContextAssembler(mapper, null, null, null, null, null, null, null, null);
        for (String status : List.of("partial", "cancelled", "interrupted", "failed", "completed", "running")) {
            for (var steps : List.of(List.of(step("finished", "{}")), List.<ChatStepDto>of())) {
                var messages = new ArrayList<WireMessage>();
                assembler.appendHistoryMessage(messages, new ChatStoreService.StoredMessage("assistant", "", steps, null,
                        null, null, null, null, null, "history", status, null));
                messages.add(WireMessage.user("下一轮请求"));
                new UpstreamLlmClient(mapper, new ModelCapabilityRegistry()).chatRequest(messages, List.of());
            }
        }
    }

    @Test
    void replaysMixedHistoryWithoutMalformedOrOrphanedToolMessages() throws Exception {
        var mapper = new ObjectMapper();
        var assembler = new ChatContextAssembler(mapper, null, null, null, null, null, null, null, null);
        var client = new UpstreamLlmClient(mapper, new ModelCapabilityRegistry());
        for (String args : List.of("{\"command\":\"echo \"[REDACTED]\"\"}", "[]", "null", "{} {}", "{} trailing")) {
            var steps = List.of(step("valid", "{\"command\":\"echo hello\"}"), step("broken", args));
            var messages = new ArrayList<WireMessage>();
            assembler.appendHistoryMessage(messages, new ChatStoreService.StoredMessage("assistant", "已执行", steps, null));
            for (var message : messages) {
                for (var call : message.toolCalls()) {
                    mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(call.arguments());
                }
            }
            client.chatRequest(messages, List.of());
        }
    }

    @Test
    void preservesResultsWhenAllHistoricalArgumentsAreBroken() {
        var mapper = new ObjectMapper();
        var assembler = new ChatContextAssembler(mapper, null, null, null, null, null, null, null, null);
        var messages = new ArrayList<WireMessage>();
        assembler.appendHistoryMessage(messages, new ChatStoreService.StoredMessage("assistant", "",
                List.of(step("broken", "{invalid")), null));
        new UpstreamLlmClient(mapper, new ModelCapabilityRegistry()).chatRequest(messages, List.of());
    }

    private ChatStepDto step(String id, String args) {
        return new ChatStepDto(id, "tool", "执行命令", null, 1L, "completed", "sh",
                new ChatStepDto.StepInput(null, null, null, "echo hello", args),
                new ChatStepDto.StepResult("hello", "已执行", null, 1, false, null), 1);
    }
}
