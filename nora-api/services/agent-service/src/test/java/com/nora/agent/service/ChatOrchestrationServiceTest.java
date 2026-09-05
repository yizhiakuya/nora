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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        LlmProperties properties = new LlmProperties("", "http://localhost:9/v1", "test-model");
        service = new ChatOrchestrationService(properties, ragRetrievalClient,
                sqlToolClient, serviceLogClient, new ObjectMapper());
    }

    @Test
    void emitsRetrievalStepAndSourcesWhenRagReturnsHits() {
        List<CitationDto> citations = List.of(
                new CitationDto("arch.md", "file", 2, 0.9, "pgvector provides cosine search"));
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

        assertTrue(future.isCompletedExceptionally() || !future.isDone(), "LLM call must fail fast without key");
        // 检索 step 一定先发;后续是工具轮失败 step + 回答失败 step(键未配置)
        assertEquals("tool", steps.get(0).type());
        assertEquals("检索知识库", steps.get(0).title());
        assertEquals("completed", steps.get(0).status());
        assertEquals(1, sourcesEvents.size());
        assertEquals("arch.md", sourcesEvents.get(0).get(0).docName());
    }

    @Test
    void emptyRagYieldsStepWithoutSourcesEvent() {
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

        assertTrue(steps.size() >= 1, "retrieval step emitted");
        assertTrue(sourcesEvents.isEmpty(), "no sources event without hits");
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
}
