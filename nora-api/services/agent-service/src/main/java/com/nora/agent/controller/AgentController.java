package com.nora.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.service.ApprovalService;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;
import com.nora.agent.service.PermissionMode;
import com.nora.agent.service.TurnStreamRegistry;
import com.nora.common.logging.TraceContext;
import com.nora.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final TurnStreamRegistry turnStreams;
    /** 轮次执行引擎(从本类拆出,2026-09-17)。 */
    private final ChatTurnRunner turnRunner;
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();
    /** 进行中的轮次,按会话注册;「停止生成」据此中断上游 HTTP 读(省 token) */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.Future<?>> activeTurns =
            new java.util.concurrent.ConcurrentHashMap<>();


    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ObjectMapper objectMapper) {
        this(orchestrationService, chatStoreService, null, objectMapper, new TurnStreamRegistry());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(ChatOrchestrationService orchestrationService,
                           ChatStoreService chatStoreService,
                           ApprovalService approvalService,
                           ObjectMapper objectMapper,
                           TurnStreamRegistry turnStreams) {
        this.orchestrationService = orchestrationService;
        this.chatStoreService = chatStoreService;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
        this.turnStreams = turnStreams;
        this.turnRunner = new ChatTurnRunner(orchestrationService, chatStoreService, turnStreams, objectMapper);
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
        // 轮次入口:sessionId + turnId 进 MDC(经 TraceContext.wrap 传播到 chatExecutor
        // 线程),本轮编排/落库/SSE 发送的全部日志自动携带,排障时按会话一屏串联
        String turnId = TraceContext.newTraceId();
        chatExecutor.execute(TraceContext.wrap(() -> {
            TraceContext.setSessionId(sessionId);
            TraceContext.setTurnId(turnId);
            log.info(">> turn start (contentChars={})", request.content().length());
            var handle = new java.util.concurrent.FutureTask<>(() -> {
                turnRunner.runChatTurn(sessionId, request.content().trim(),
                        request.model(), request.reasoningLevel(),
                        PermissionMode.parse(request.permissionMode()), request.providerId(), emitter);
                return null;
            });
            activeTurns.put(sessionId, handle);
            try {
                handle.run();
            } finally {
                activeTurns.remove(sessionId, handle);
                TraceContext.clear();
            }
        }));
        return emitter;
    }

    /** 一次性 agent 运行专用线程池:chat() 是同步阻塞的,提交到这里才能超时可取消 */
    private final ExecutorService agentRunExecutor = Executors.newCachedThreadPool();

    /**
     * POST /api/chat/agent/run — 供服务间调用(automation 动作)的非流式一次性
     * agent 运行。走同一套编排(RAG + 工具循环),FULL 权限档、无审批门(无会话),
     * 静默收集事件,返回最终回答 + token 用量;以 SSE 超时为上限,超时会真正
     * 中断编排线程使上游 LLM 读中止。
     *
     * <p>响应 {@code {status:"completed", answer, usage}} 或
     * {@code {status:"error", error}};status 字段让调用方无需靠答案文本猜测成败。
     */
    @PostMapping("/agent/run")
    public Map<String, Object> runAgent(@RequestBody AgentRunRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (request.prompt().trim().length() > 8000) {
            throw new IllegalArgumentException("prompt exceeds 8000 characters");
        }
        // chat() 内部是同步执行并返回已完成的 future,用 join() 取值;
        // 包一层 Future 才能实现超时取消(中断编排线程使上游 LLM 读中止)。
        // permissionMode 缺省 FULL(automation 场景);只读分析场景显式传 ASSIST
        PermissionMode mode = request.permissionMode() == null || request.permissionMode().isBlank()
                ? PermissionMode.FULL
                : PermissionMode.parse(request.permissionMode());
        java.util.concurrent.Future<ChatOrchestrationService.ChatTurn> future =
                agentRunExecutor.submit(TraceContext.wrap(() -> orchestrationService.chat(
                        request.prompt().trim(),
                        List.of(), List.of(),
                        request.model(), request.reasoningLevel(),
                        mode,
                        null,
                        SILENT_CONSUMER).join()));
        try {
            ChatOrchestrationService.ChatTurn turn = future.get(SSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "completed");
            out.put("answer", turn.answer());
            if (turn.usage() != null) {
                out.put("usage", Map.of(
                        "inputTokens", Objects.requireNonNullElse(turn.usage().inputTokens(), 0),
                        "outputTokens", Objects.requireNonNullElse(turn.usage().outputTokens(), 0),
                        "totalTokens", Objects.requireNonNullElse(turn.usage().totalTokens(), 0)));
            }
            return out;
        } catch (TimeoutException e) {
            future.cancel(true); // 中断编排线程 → 上游 LLM HTTP 读中止,不再白烧 token
            throw new IllegalStateException("agent run timed out after " + SSE_TIMEOUT_MS + "ms");
        } catch (CancellationException e) {
            throw new IllegalStateException("agent run was cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志,不吞信号
            throw new IllegalStateException("agent run interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("agent run failed: " + cause.getMessage(), cause);
        }
    }

    /** 静默消费全部编排事件:机对机运行没有 SSE 流可发 */
    private static final ChatOrchestrationService.ChatEventConsumer SILENT_CONSUMER =
            new ChatOrchestrationService.ChatEventConsumer() {
                @Override public void step(ChatStepDto step) { }
                @Override public void delta(String token) { }
                @Override public void sources(List<CitationDto> found) { }
            };

    /** POST /api/chat/agent/run 请求体。permissionMode 缺省 FULL;只读分析场景传 ASSIST。 */
    public record AgentRunRequest(String prompt, String model, String reasoningLevel, String permissionMode) {
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

    /**
     * Live-turn probe for reconnects: does the session have an in-flight turn,
     * and what did it already emit? The client diffs this against its local
     * message tail to rebuild the streaming UI (isTyping, steps, partial
     * answer) without waiting for the turn to finish.
     */
    @GetMapping("/sessions/{sessionId}/turn/live")
    public ApiResponse<LiveTurnView> liveTurn(@PathVariable String sessionId) {
        var turn = turnStreams.get(sessionId);
        if (turn == null) {
            return ApiResponse.ok(new LiveTurnView(false, null, null, 0, null, null, 0));
        }
        var buffer = turn.snapshotAfter(0);
        return ApiResponse.ok(new LiveTurnView(
                !turn.isFinished(),
                turn.content,
                turn.startedAtMs,
                buffer.size(),
                buffer.isEmpty() ? null : buffer.get(buffer.size() - 1),
                turn.turnId,
                turn.lastSeq()));
    }
    /**
     * SSE attach to a live turn: replays the buffered events first, then
     * follows in real time until {@code done}/{@code error}.
     *
     * <p>Reconnect/resume protocol: every event carries {@code id:<seq>} (the
     * per-turn monotonic sequence). A reconnecting EventSource sends
     * {@code Last-Event-ID: <turnId>:<lastSeq>}; events after that seq are
     * replayed, so a dropped connection resumes exactly where it stopped with
     * no gaps and no duplicates.
     *
     * <p>Ordering is guaranteed by fencing the two sources of events: while the
     * replay drain runs (synchronized on the turn), live appends block; the
     * subscription is activated only after the drain completes. Events can
     * therefore never overtake or duplicate each other.
     */
    @GetMapping(value = "/sessions/{sessionId}/turn/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter attachTurnStream(
            @PathVariable String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        var turn = turnStreams.get(sessionId);
        if (turn == null) {
            // 无进行中轮次:立刻收尾,前端据此走普通历史加载
            try {
                emitter.send(SseEmitter.event().name("idle").data("{\"live\":false}"));
            } catch (IOException ignored) {
                // client gone
            }
            emitter.complete();
            return emitter;
        }
        // 解析 Last-Event-ID("turnId:seq"):仅当属于本轮时才作增量游标,
        // 陈旧轮的游标对新一轮无意义,必须从 0 全量回放
        long afterSeq = 0;
        if (lastEventId != null) {
            int sep = lastEventId.lastIndexOf(':');
            if (sep > 0 && lastEventId.substring(0, sep).equals(turn.turnId)) {
                try {
                    afterSeq = Math.max(0, Long.parseLong(lastEventId.substring(sep + 1)));
                } catch (NumberFormatException ignored) {
                    // 坏游标按全量回放处理
                }
            }
        }
        java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.function.Consumer<TurnStreamRegistry.TurnEvent> forward = event -> {
            // exactly-once:回放与实况可能短暂重叠(finish 后的新订阅),seq 去重兜底;
            // id 下发给客户端作断线续传游标
            if (seen.add(event.seq())) {
                sendRaw(emitter, event.event(), event.json(), event.seq());
            }
        };
        // 回放 drain 与实况订阅在同一把锁内原子完成:drain 里没有的事件必然
        // 会走订阅到达,实况事件不可能插队到更早的缓冲事件之前
        java.util.List<TurnStreamRegistry.TurnEvent> backlog;
        try {
            backlog = turn.subscribeDraining(afterSeq, forward);
            for (TurnStreamRegistry.TurnEvent e : backlog) {
                forward.accept(e);
            }
        } catch (Exception e) {
            log.debug("turn replay failed for {}: {}", sessionId, e.getMessage());
            emitter.complete();
            return emitter;
        }
        // subscribeDraining 之后 turn 可能已 finish(终态事件在 drain 后、
        // finished 置位前入缓冲,订阅者已能收到;此处兜底关闭 emitter)
        if (turn.isFinished()) {
            emitter.complete();
        }
        emitter.onCompletion(() -> turn.unsubscribe(forward));
        emitter.onTimeout(() -> turn.unsubscribe(forward));
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

    /**
     * Truncates history from the message at {@code index} (0-based, chronological)
     * inclusive. Used by the frontend "edit & resend" flow: the user rewinds to an
     * earlier user message, edits it, and resends — the server context must match,
     * so the old branch (that message and everything after) is deleted first.
     */
    @DeleteMapping("/sessions/{sessionId}/messages/{index}")
    public ApiResponse<Integer> truncateFrom(@PathVariable String sessionId,
                                             @PathVariable int index) {
        int deleted = chatStoreService.truncateFrom(sessionId, index);
        if (deleted < 0) {
            throw new IllegalArgumentException("message index out of range: " + index);
        }
        return ApiResponse.ok(deleted);
    }





    /**
     * 发送已序列化的 JSON(TurnEvent 回放路径),不再二次 toJson。
     * {@code id:<seq>} 供 EventSource 断线续传:浏览器重连时自动带上
     * Last-Event-ID,服务端据此增量回放,不重不漏。
     */
    private void sendRaw(SseEmitter emitter, String event, String json, long seq) {
        try {
            emitter.send(SseEmitter.event().id(String.valueOf(seq)).name(event).data(json, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE replay send failed (client disconnected?): {}", e.getMessage());
        }
    }



    /** GET /sessions/{id}/turn/live 响应:lastEvent 供增量续传(前端按游标去重)。 */
    public record LiveTurnView(
            boolean running,
            String content,
            Long startedAtMs,
            int bufferedEvents,
            TurnStreamRegistry.TurnEvent lastEvent,
            String turnId,
            long lastSeq) {
    }

    /** POST /api/chat/sessions/{id}/messages body. providerId = 前端选定的渠道(同名模型跨渠道时精确定位)。 */
    public record MessageRequest(String content, String model, String reasoningLevel, String permissionMode,
                                 Long providerId) {
        public MessageRequest(String content, String model, String reasoningLevel) {
            this(content, model, reasoningLevel, null, null);
        }

        public MessageRequest(String content, String model, String reasoningLevel, String permissionMode) {
            this(content, model, reasoningLevel, permissionMode, null);
        }
    }

    /** POST /api/chat/approvals/{token} body. */
    public record ApprovalDecision(Boolean approved) {
    }




}
