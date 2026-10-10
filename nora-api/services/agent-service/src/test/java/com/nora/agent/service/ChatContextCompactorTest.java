package com.nora.agent.service;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ContextCheckpointDto;
import org.junit.jupiter.api.Test;

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
        var assembler = new ChatContextAssembler(new ObjectMapper(), null, null, null, null, null, null);
        var messages = new java.util.ArrayList<WireMessage>();
        assembler.appendCheckpoint(messages, checkpoint);
        for (var message : history) assembler.appendHistoryMessage(messages, message);
        new ObjectMapper().writeValueAsString(messages);
    }

    private ChatStoreService.StoredMessage message(String role, String content, String sender, String id) {
        return new ChatStoreService.StoredMessage(role, content, List.of(), List.of(), null, null, null, LocalDateTime.now(), sender, id);
    }
}
