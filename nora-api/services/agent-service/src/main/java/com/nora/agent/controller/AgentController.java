package com.nora.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;
import com.nora.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat endpoints: SSE streaming (step/delta/sources/done) plus session
 * history, matching the frontend agentApi.ts contract.
 */
@RestController
@RequestMapping("/api/chat")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** SSE heartbeat interval; keeps proxies from closing an idle stream. */
    private static final long SSE_TIMEOUT_MS = 180_000;

    private final ChatOrchestrationService orchestrationService;
    private final ChatStoreService chatStoreService;
    private final ObjectMapper objectMapper;
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();

    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ObjectMapper objectMapper) {
        this.orchestrationService = orchestrationService;
        this.chatStoreService = chatStoreService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * Sends one user message and streams the agent answer as SSE events:
     * {@code step}, {@code delta}, {@code sources}, {@code done}.
     *
     * @param sessionId chat session id
     * @param request   {@code {content}}
     * @return SSE stream
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable String sessionId,
                                  @RequestBody MessageRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        chatExecutor.execute(() -> runChatTurn(sessionId, request.content().trim(), emitter));
        return emitter;
    }

    /** Chat history of one session, oldest first (frontend ChatMessage[]). */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatStoreService.StoredMessage> messages(@PathVariable String sessionId) {
        return chatStoreService.loadMessages(sessionId);
    }

    /** All sessions, most recently active first (frontend sidebar list). */
    @GetMapping("/sessions")
    public ApiResponse<List<ChatStoreService.SessionSummary>> sessions() {
        return ApiResponse.ok(chatStoreService.listSessions());
    }

    /** Deletes a session with its messages. */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        if (!chatStoreService.deleteSession(sessionId)) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        return ApiResponse.ok();
    }

    private void runChatTurn(String sessionId, String content, SseEmitter emitter) {
        try {
            chatStoreService.ensureSession(sessionId, content);
            chatStoreService.saveMessage(sessionId, "user", content, null, null);

            List<ChatStepDto> steps = new java.util.ArrayList<>();
            List<CitationDto> citations = new java.util.ArrayList<>();
            StringBuilder answer = new StringBuilder();

            orchestrationService.chat(
                    content,
                    chatStoreService.loadMessages(sessionId),
                    new ChatOrchestrationService.ChatEventConsumer() {
                        @Override
                        public void step(ChatStepDto step) {
                            steps.add(step);
                            send(emitter, "step", step);
                        }

                        @Override
                        public void delta(String token) {
                            send(emitter, "delta", new DeltaPayload(token));
                        }

                        @Override
                        public void sources(List<CitationDto> found) {
                            citations.addAll(found);
                            send(emitter, "sources", found);
                        }
                    })
                    .whenComplete((turn, error) -> {
                        String answerText = turn != null ? turn.answer() : answer.toString();
                        if (error != null) {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(toJson(new ErrorPayload(error.getMessage()))));
                            } catch (IOException ignored) {
                                // client gone
                            }
                        }
                        try {
                            chatStoreService.saveMessage(sessionId, "assistant", answerText, steps, citations);
                        } catch (Exception e) {
                            log.warn("failed to persist assistant message for session {}: {}",
                                    sessionId, e.getMessage());
                        }
                        send(emitter, "done", new DonePayload(UUID.randomUUID().toString()));
                        emitter.complete();
                    });
        } catch (Exception e) {
            log.error("chat turn failed for session {}: {}", sessionId, e.getMessage(), e);
            send(emitter, "error", new ErrorPayload(e.getMessage()));
            emitter.complete();
        }
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(toJson(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** POST /api/chat/sessions/{id}/messages body. */
    public record MessageRequest(String content) {
    }

    /** SSE delta payload. */
    public record DeltaPayload(String content) {
    }

    /** SSE done payload. */
    public record DonePayload(String messageId) {
    }

    /** SSE error payload. */
    public record ErrorPayload(String message) {
    }
}
