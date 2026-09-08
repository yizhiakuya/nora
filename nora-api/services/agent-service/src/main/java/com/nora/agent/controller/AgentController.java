package com.nora.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.service.ApprovalService;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;
import com.nora.agent.service.PermissionMode;
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
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();
    /** 进行中的轮次,按会话注册;「停止生成」据此中断上游 HTTP 读(省 token) */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.Future<?>> activeTurns =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ObjectMapper objectMapper) {
        this(orchestrationService, chatStoreService, null, objectMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ApprovalService approvalService,
                           ObjectMapper objectMapper) {
        this.orchestrationService = orchestrationService;
        this.chatStoreService = chatStoreService;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * Sends one user message and streams the agent answer as SSE events:
     * {@code step}, {@code delta}, {@code sources}, {@code done},
     * plus {@code approval_required} when a high-risk tool call needs
     * the user's decision (three-mode permission: ask/assist/full).
     *
     * @param sessionId chat session id
     * @param request   {@code {content, model, reasoningLevel, permissionMode}}
     * @return SSE stream
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable String sessionId,
                                  @RequestBody MessageRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        chatExecutor.execute(() -> {
            var handle = new java.util.concurrent.FutureTask<>(() -> {
                runChatTurn(sessionId, request.content().trim(),
                        request.model(), request.reasoningLevel(),
                        PermissionMode.parse(request.permissionMode()), emitter);
                return null;
            });
            activeTurns.put(sessionId, handle);
            try {
                handle.run();
            } finally {
                activeTurns.remove(sessionId, handle);
            }
        });
        return emitter;
    }

    /**
     * 取消某会话进行中的轮次:中断编排线程使上游 LLM HTTP 读中止(不再白烧
     * token),并清掉挂起的审批。无进行中轮次时幂等返回 ok(false)。
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ApiResponse<Boolean> cancelTurn(@PathVariable String sessionId) {
        var future = activeTurns.remove(sessionId);
        boolean cancelled = future != null;
        if (cancelled) {
            future.cancel(true);
        }
        approvalService.clearPending(sessionId);
        return ApiResponse.ok(cancelled);
    }

    /**
     * Approves or declines a pending high-risk operation. The one-shot token
     * comes from the approval_required SSE event; unknown/consumed tokens 404
     * (never a silent success).
     */
    @PostMapping("/approvals/{approvalToken}")
    public ApiResponse<Boolean> resolveApproval(@PathVariable String approvalToken,
                                                @RequestBody ApprovalDecision decision,
                                                @org.springframework.web.bind.annotation.RequestParam("sessionId") String sessionId) {
        boolean approved = Boolean.TRUE.equals(decision.approved());
        boolean resolved = approvalService.resolve(sessionId, approvalToken, approved);
        if (!resolved) {
            throw new IllegalArgumentException("approval not found, session mismatch, or already resolved");
        }
        return ApiResponse.ok(approved);
    }

    /** Pending approvals of one session (reconnect fallback). */
    @GetMapping("/sessions/{sessionId}/approvals")
    public ApiResponse<List<ApprovalRequestDto>> pendingApprovals(@PathVariable String sessionId) {
        return ApiResponse.ok(approvalService.pendingFor(sessionId));
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

    private void runChatTurn(String sessionId, String content, String model, String reasoningLevel,
                             com.nora.agent.service.PermissionMode permissionMode, SseEmitter emitter) {
        try {
            chatStoreService.ensureSession(sessionId, content);
            chatStoreService.saveMessage(sessionId, "user", content, null, null);

            List<ChatStepDto> steps = new java.util.ArrayList<>();
            List<CitationDto> citations = new java.util.ArrayList<>();
            java.util.Map<Integer, StringBuilder> reasoningBuffers = new java.util.LinkedHashMap<>();
            StringBuilder answer = new StringBuilder();
            int[] stepIndex = {0};
            long turnStart = System.currentTimeMillis();

            var turnFuture = orchestrationService.chat(
                    content,
                    chatStoreService.loadMessages(sessionId),
                    chatStoreService.loadRecentReflections(sessionId, "chat-turn", 3),
                    model,
                    reasoningLevel,
                    permissionMode,
                    sessionId,
                    new ChatOrchestrationService.ChatEventConsumer() {
                        @Override
                        public void step(ChatStepDto step) {
                            steps.add(step);
                            try { chatStoreService.saveStep(sessionId, stepIndex[0]++, step); }
                            catch (Exception e) { log.warn("failed to persist agent step: {}", e.getMessage()); }
                            send(emitter, "step", step);
                        }

                        @Override
                        public void delta(String token) {
                            send(emitter, "delta", new DeltaPayload(token));
                        }

                        @Override
                        public void reasoningDelta(Integer roundIndex, String token) {
                            if (token == null || token.isEmpty()) return;
                            int key = roundIndex == null ? Integer.MAX_VALUE : roundIndex;
                            reasoningBuffers.computeIfAbsent(key, k -> new StringBuilder()).append(token);
                            send(emitter, "reasoning_delta", new ReasoningDeltaPayload(roundIndex, token));
                        }

                        @Override
                        public void approvalRequired(com.nora.agent.dto.ApprovalRequestDto request) {
                            send(emitter, "approval_required", request);
                        }

                        @Override
                        public void sources(List<CitationDto> found) {
                            citations.addAll(found);
                            send(emitter, "sources", found);
                        }
                    });
            if (turnFuture == null) {
                throw new IllegalStateException("agent orchestration returned no future");
            }
            turnFuture.whenComplete((turn, error) -> {
                        // 用户主动取消(线程中断→上游读中止):静默收尾,不发 error 不记失败
                        boolean userCancelled = error instanceof java.util.concurrent.CancellationException
                                || (error != null && Thread.currentThread().isInterrupted());
                        String answerText = turn != null ? turn.answer() : answer.toString();
                        long durationMs = System.currentTimeMillis() - turnStart;
                        var usage = turn != null ? turn.usage() : null;
                        if (error != null && !userCancelled) {
                            try {
                                chatStoreService.saveReflection(sessionId, "chat-turn",
                                        "本轮 Agent 执行失败：" + (error.getMessage() == null ? "unknown error" : error.getMessage()));
                            } catch (Exception reflectionError) {
                                log.warn("failed to persist agent reflection for {}: {}", sessionId, reflectionError.getMessage());
                            }
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(toJson(new ErrorPayload(error.getMessage()))));
                            } catch (IOException ignored) {
                                // client gone
                            }
                        }
                        try {
                            if (!userCancelled) {
                                for (var entry : reasoningBuffers.entrySet()) {
                                    if (entry.getValue().isEmpty()) continue;
                                    Integer roundIndex = entry.getKey() == Integer.MAX_VALUE ? null : entry.getKey();
                                    ChatStepDto reasoningStep = new ChatStepDto(
                                            "s-reasoning-" + (roundIndex == null ? "final" : roundIndex),
                                            "think", "推理过程", entry.getValue().toString(), durationMs,
                                            "completed", null, null, null, roundIndex);
                                    steps.add(reasoningStep);
                                    try { chatStoreService.saveStep(sessionId, stepIndex[0]++, reasoningStep); }
                                    catch (Exception e) { log.warn("failed to persist reasoning step: {}", e.getMessage()); }
                                }
                                chatStoreService.saveMessage(sessionId, "assistant", answerText, steps, citations);
                            }
                        } catch (Exception e) {
                            log.warn("failed to persist assistant message for session {}: {}",
                                    sessionId, e.getMessage());
                        }
                        // done carries turn metrics (harness pattern: server stamps timing so
                        // the client never recomputes); usage is the provider's real token
                        // accounting summed across tool rounds (null when relay omits it);
                        // contextWindow/promptTokens feed the frontend context meter
                        if (!userCancelled) {
                            send(emitter, "done", new DonePayload(UUID.randomUUID().toString(),
                                    durationMs,
                                    usage != null ? new DonePayload.Usage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens()) : null,
                                    answerText.length(),
                                    turn != null ? turn.contextWindow() : null,
                                    turn != null ? turn.promptTokens() : null,
                                    turn != null ? turn.ttftMs() : null));
                        }
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
    public record MessageRequest(String content, String model, String reasoningLevel, String permissionMode) {
        public MessageRequest(String content, String model, String reasoningLevel) {
            this(content, model, reasoningLevel, null);
        }
    }

    /** POST /api/chat/approvals/{token} body. */
    public record ApprovalDecision(Boolean approved) {
    }

    /** SSE delta payload. */
    public record DeltaPayload(String content) {
    }

    /** SSE reasoning delta payload; roundIndex is null for the final answer round. */
    public record ReasoningDeltaPayload(Integer roundIndex, String content) {
    }

    /**
     * SSE done payload with turn metrics. {@code usage} stays null until the
     * model gateway returns token accounting; the frontend falls back to its
     * local estimate while it is null.
     */
    public record DonePayload(String messageId, Long durationMs, Usage usage, Integer answerChars,
                              Long contextWindow, Integer promptTokens, Long ttftMs) {

        /** Token accounting from the provider (harness: usage is a first-class done field). */
        public record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        }
    }

    /** SSE error payload. */
    public record ErrorPayload(String message) {
    }
}
