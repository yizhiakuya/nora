package com.nora.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentApiRecordsTest {

    @Test
    void citationRecordExposesAllFields() {
        Citation citation = new Citation(
                "arch-notes.md", "docs://kb/arch-notes.md", 3, 0.87, "gateway routes /api/chat/**");

        assertEquals("arch-notes.md", citation.docName());
        assertEquals("docs://kb/arch-notes.md", citation.source());
        assertEquals(3, citation.chunkIndex());
        assertEquals(0.87, citation.score());
        assertEquals("gateway routes /api/chat/**", citation.snippet());
        assertEquals(citation, new Citation("arch-notes.md", "docs://kb/arch-notes.md",
                3, 0.87, "gateway routes /api/chat/**"));
        assertNotEquals(citation, new Citation("other.md", "docs://kb/arch-notes.md",
                3, 0.87, "gateway routes /api/chat/**"));
        assertNull(new Citation(null, null, 0, 0.0, null).docName());
    }

    @Test
    void chatStepEventRecordExposesAllFields() {
        ChatStepEvent event = new ChatStepEvent(
                "step-1", ChatStepType.THINK, "Reasoning", "plan: search docs first",
                120L, ChatStepStatus.COMPLETED);

        assertEquals("step-1", event.id());
        assertEquals(ChatStepType.THINK, event.type());
        assertEquals("Reasoning", event.title());
        assertEquals("plan: search docs first", event.detail());
        assertEquals(120L, event.durationMs());
        assertEquals(ChatStepStatus.COMPLETED, event.status());
        assertNull(new ChatStepEvent(null, null, null, null, null, null).id());
    }

    @Test
    void chatStepEnumsRoundTrip() {
        assertEquals(2, ChatStepType.values().length);
        assertEquals(4, ChatStepStatus.values().length);

        for (ChatStepType type : ChatStepType.values()) {
            assertEquals(type, ChatStepType.valueOf(type.name()));
        }
        for (ChatStepStatus status : ChatStepStatus.values()) {
            assertEquals(status, ChatStepStatus.valueOf(status.name()));
        }

        assertEquals(ChatStepType.TOOL, ChatStepType.valueOf("TOOL"));
        assertEquals(ChatStepStatus.FAILED, ChatStepStatus.valueOf("FAILED"));
    }

    @Test
    void modelInfoRecordExposesAllFields() {
        ModelInfo model = new ModelInfo("openai", "gpt-4o-mini");

        assertEquals("openai", model.protocol());
        assertEquals("gpt-4o-mini", model.modelName());
        assertEquals(List.of(model), List.of(new ModelInfo("openai", "gpt-4o-mini")));
    }
}
