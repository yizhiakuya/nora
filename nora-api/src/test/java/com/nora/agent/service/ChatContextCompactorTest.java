package com.nora.agent.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ContextCheckpointDto;

class ChatContextCompactorTest {
    @Test
    void checkpointProjectsHistoryAndSkipsControlEvents() throws Exception {
        var history = List.of(message("user", "要求", "user", "u1"), message("assistant", "结果", null, "a1"),
                message("assistant", "/compact", "compaction", "c1"), message("user", "补充", "user", "u2"));
        var checkpoint = new ContextCheckpointDto("a1", "有效约束与结果", ".cache/context/test.json", 20_000,
                null, 1000, 100, LocalDateTime.now());
        ChatContextCompactor.history(history, null);
        ChatContextCompactor.history(history, checkpoint);
        ChatContextCompactor.history(List.of(), null);
        var assembler = new ChatContextAssembler(new ObjectMapper(), null, null, null, null, null, null, null);
        var messages = new java.util.ArrayList<WireMessage>();
        assembler.appendCheckpoint(messages, checkpoint);
        for (var message : history) assembler.appendHistoryMessage(messages, message);
        dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(messages.stream().map(WireMessage::message).toList());
    }

    private ChatStoreService.StoredMessage message(String role, String content, String sender, String id) {
        return new ChatStoreService.StoredMessage(role, content, List.of(), List.of(), null, null, null, LocalDateTime.now(), sender, id);
    }

    @Test
    void workingCheckpointReplaysOnlyLaterRoundsOfItsRun() throws Exception {
        var mapper = new ObjectMapper();
        var assembler = new ChatContextAssembler(mapper, null, null, null, null, null, null, null);
        var checkpoint = new ContextCheckpointDto("u1", "已完成第一轮，继续第二轮", "context/working.json", 20000,
                null, 1000, 100, LocalDateTime.now(), "r1", 1);
        var steps = List.of(tool(1), tool(2));
        var saved = new ChatStoreService.StoredMessage("assistant", "当前结果", steps, List.of(), null, null, null,
                LocalDateTime.now(), "assistant", "a1", "completed", null, null, "r1");
        var messages = new java.util.ArrayList<WireMessage>();
        assembler.appendCheckpoint(messages, checkpoint);
        assembler.appendHistoryMessage(messages, saved, checkpoint);
        System.out.println(dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(messages.stream().map(WireMessage::message).toList()));
    }

    private com.nora.agent.dto.ChatStepDto tool(int round) {
        return new com.nora.agent.dto.ChatStepDto("s" + round, "tool", "读取文件", null, 0L, "completed", "manage_workspace",
                new com.nora.agent.dto.ChatStepDto.StepInput(null, null, null, "a.txt", "{\"action\":\"read\",\"path\":\"a.txt\"}"),
                new com.nora.agent.dto.ChatStepDto.StepResult("已验证的结果 " + round, null, null, null, true, null), round);
    }

    @Test
    void archivesSdkMessagesAsReadableTranscript() {
        var mapper = new ObjectMapper();
        var assembler = new ChatContextAssembler(mapper, null, null, null, null, null, null, null);
        var message = new WireMessage(dev.langchain4j.data.message.AiMessage.builder()
                .text("已验证结果").thinking("归档中的过程").build());
        assembler.transcript(List.of(message));
    }
}
