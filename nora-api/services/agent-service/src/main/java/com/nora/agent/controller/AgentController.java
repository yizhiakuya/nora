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
import java.util.UUID;
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
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();
    /** 进行中的轮次,按会话注册;「停止生成」据此中断上游 HTTP 读(省 token) */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.Future<?>> activeTurns =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 会话标题生成专用线程池。
     *
     * <p>独立于 {@link #chatExecutor}：起标题是「锦上添花」的旁路任务，不能与
     * 对话轮次争抢，也不能被「停止生成」（中断 chatExecutor 上的 Future）连带取消
     * ——用户停掉回答后，标题仍应正常生成。
     */
    private final ExecutorService titleExecutor = Executors.newCachedThreadPool();

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

    private void runChatTurn(String sessionId, String content, String model, String reasoningLevel,
                             com.nora.agent.service.PermissionMode permissionMode, SseEmitter emitter) {
        // 每轮注册 live turn:事件进有界缓冲,SSE 断开(刷新/切页)后可重连回放;
        // 编排线程不受断开影响,收尾照常落库
        TurnStreamRegistry.LiveTurn liveTurn = turnStreams.start(sessionId, content);
        try {
            // 标题：先落「用户消息开头若干字 + …」当占位（立刻有名字可看），
            // 再异步让 AI 生成短标题覆盖它。不再把整条原文塞进标题列——
            // 列宽 VARCHAR(255)，超长消息会让会话创建直接失败（实测）。
            boolean firstTurn = chatStoreService.ensureSession(
                    sessionId, ChatStoreService.placeholderTitle(content));
            chatStoreService.saveMessage(sessionId, "user", content, null, null);
            // 仅本会话首次命名：后续轮次改标题会覆盖上一轮已经起好的名字
            if (firstTurn) {
                scheduleTitleGeneration(sessionId, content, model, liveTurn, emitter);
            }

            List<ChatStepDto> steps = new java.util.ArrayList<>();
            List<CitationDto> citations = new java.util.ArrayList<>();
            java.util.Map<Integer, StringBuilder> reasoningBuffers = new java.util.LinkedHashMap<>();
            // 推理步骤的落位与计时:首次 reasoning_delta 就把步骤按真实时间顺序占位进
            // steps(收尾原位补正文),而不是收尾统一 append——否则落库顺序变成
            // 「工具全在前、推理全在后」,前端拉历史收敛终态后时间线错位(实测 2026-09-15:
            // 5 个工具聚在上、4 条思考聚在下,而真实顺序是 思考→工具→思考→工具…)。
            java.util.Map<Integer, Integer> reasoningStepAt = new java.util.LinkedHashMap<>();
            java.util.Map<Integer, Long> reasoningStartMs = new java.util.LinkedHashMap<>();
            java.util.Map<Integer, Long> reasoningEndMs = new java.util.LinkedHashMap<>();
            // 上一事件时刻:推理起点取「距上一个事件的时间」而非首个推理 token——
            // 上游把整段推理突发投递(几十 ms 内一次性到达)时,首末 token 间隔会
            // 算成 8ms 这种假数字;从上一事件起算则两种投递形态都接近真实耗时
            // (突发≈整轮思考时长,流式≈真实思考窗口)。
            long[] lastEventMs = {System.currentTimeMillis()};
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
                            lastEventMs[0] = System.currentTimeMillis();
                            steps.add(step);
                            try { chatStoreService.saveStep(sessionId, stepIndex[0]++, step); }
                            catch (Exception e) { log.warn("failed to persist agent step: {}", e.getMessage()); }
                            send(emitter, "step", step);
                            turnStreams.publish(liveTurn, "step", toJson(step));
                        }

                        @Override
                        public void delta(String token) {
                            lastEventMs[0] = System.currentTimeMillis();
                            answer.append(token);
                            send(emitter, "delta", new DeltaPayload(token));
                            turnStreams.publish(liveTurn, "delta", toJson(new DeltaPayload(token)));
                        }

                        @Override
                        public void reasoningDelta(Integer roundIndex, String token) {
                            if (token == null || token.isEmpty()) return;
                            int key = roundIndex == null ? Integer.MAX_VALUE : roundIndex;
                            reasoningBuffers.computeIfAbsent(key, k -> new StringBuilder()).append(token);
                            long now = System.currentTimeMillis();
                            reasoningStartMs.putIfAbsent(key, lastEventMs[0]);
                            reasoningEndMs.put(key, now);
                            lastEventMs[0] = now;
                            // 首 token 即占位:保持「思考→工具→思考→工具…」的真实顺序;
                            // 正文收尾时一次性补上(逐 token 重建大字符串是 O(n²) 白工)
                            if (!reasoningStepAt.containsKey(key)) {
                                reasoningStepAt.put(key, steps.size());
                                steps.add(new ChatStepDto(
                                        "s-reasoning-" + (roundIndex == null ? "final" : roundIndex),
                                        "think", "推理过程", null, null, "running",
                                        null, null, null, roundIndex));
                            }
                            send(emitter, "reasoning_delta", new ReasoningDeltaPayload(roundIndex, token));
                            turnStreams.publish(liveTurn, "reasoning_delta",
                                    toJson(new ReasoningDeltaPayload(roundIndex, token)));
                        }

                        @Override
                        public void approvalRequired(com.nora.agent.dto.ApprovalRequestDto request) {
                            send(emitter, "approval_required", request);
                            turnStreams.publish(liveTurn, "approval_required", toJson(request));
                        }

                        @Override
                        public void sources(List<CitationDto> found) {
                            citations.addAll(found);
                            send(emitter, "sources", found);
                            turnStreams.publish(liveTurn, "sources", toJson(found));
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
                        if (userCancelled) {
                            // 取消轮收尾:主日志显式记录(此前取消静默到只剩 DEBUG send-fail,
                            // 时间线上 cancel 之后像断了一样);半截内容照常落库
                            log.info("turn cancelled by user, persisting partial answer (chars={}, steps={}, durationMs={})",
                                    answerText.length(), steps.size(), durationMs);
                        }
                        if (error != null && !userCancelled) {
                            try {
                                chatStoreService.saveReflection(sessionId, "chat-turn",
                                        "本轮 Agent 执行失败：" + (error.getMessage() == null ? "unknown error" : error.getMessage()));
                            } catch (Exception reflectionError) {
                                log.warn("failed to persist agent reflection for {}: {}", sessionId, reflectionError.getMessage());
                            }
                            String errorJson = toJson(ErrorPayload.from(error));
                            turnStreams.publish(liveTurn, "error", errorJson);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(errorJson, MediaType.APPLICATION_JSON));
                            } catch (IOException ignored) {
                                // client gone
                            }
                        }
                        try {
                            // 取消轮也落库:前端「停止生成」保留半截气泡(stopped),
                            // 服务端行必须与前端消息序列一一对齐,否则「编辑重发」
                            // 按下标截断时前后端错位、删错分支。取消轮持久化
                            // answerText(半截内容)+ 已收集的步骤,语义即前端所见。
                            for (var entry : reasoningBuffers.entrySet()) {
                                if (entry.getValue().isEmpty()) continue;
                                Integer roundIndex = entry.getKey() == Integer.MAX_VALUE ? null : entry.getKey();
                                long started = reasoningStartMs.getOrDefault(entry.getKey(), turnStart);
                                long ended = reasoningEndMs.getOrDefault(entry.getKey(), System.currentTimeMillis());
                                ChatStepDto reasoningStep = new ChatStepDto(
                                        "s-reasoning-" + (roundIndex == null ? "final" : roundIndex),
                                        "think", "推理过程", entry.getValue().toString(),
                                        Math.max(0, ended - started),
                                        "completed", null, null, null, roundIndex);
                                // 原位补终态(占位已按真实顺序放进 steps);每轮时长是该轮
                                // 推理流的首末 token 间隔,不再给每条都盖整轮耗时
                                Integer at = reasoningStepAt.get(entry.getKey());
                                if (at != null && at < steps.size()) {
                                    steps.set(at, reasoningStep);
                                } else {
                                    steps.add(reasoningStep);
                                }
                                try { chatStoreService.saveStep(sessionId, stepIndex[0]++, reasoningStep); }
                                catch (Exception e) { log.warn("failed to persist reasoning step: {}", e.getMessage()); }
                            }
                            // durationMs 一并落库:done 事件下发的整轮耗时若只活在事件里,
                            // 刷新/切会话后前端从历史重建消息就丢了,「查看工作过程 · Ns」
                            // 的总计时会消失(实测用户反馈)。与 done 同源,不另算。
                            chatStoreService.saveMessage(sessionId, "assistant", answerText, steps, citations,
                                    durationMs);
                        } catch (Exception e) {
                            log.warn("failed to persist assistant message for session {}: {}",
                                    sessionId, e.getMessage());
                        }
                        // done carries turn metrics (harness pattern: server stamps timing so
                        // the client never recomputes); usage is the provider's real token
                        // accounting summed across tool rounds (null when relay omits it);
                        // contextWindow/promptTokens feed the frontend context meter.
                        // 取消轮也发终态(stopped=true):接续流(切页返回/断线重连的
                        // 客户端)必须收到终态才能收敛 UI——此前取消路径不发任何终态,
                        // 接续方永远卡在「正在思考」(实测 bug:取消后 UI 无法复位)。
                        DonePayload done = new DonePayload(UUID.randomUUID().toString(),
                                durationMs,
                                usage != null ? new DonePayload.Usage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens()) : null,
                                answerText.length(),
                                turn != null ? turn.contextWindow() : null,
                                turn != null ? turn.promptTokens() : null,
                                turn != null ? turn.ttftMs() : null,
                                userCancelled);
                        turnStreams.publish(liveTurn, "done", toJson(done));
                        send(emitter, "done", done);
                        emitter.complete();
                    });
        } catch (Exception e) {
            log.error("chat turn failed for session {}: {}", sessionId, e.getMessage(), e);
            ErrorPayload payload = ErrorPayload.from(e);
            turnStreams.publish(liveTurn, "error", toJson(payload));
            send(emitter, "error", payload);
            emitter.complete();
        } finally {
            turnStreams.finish(sessionId, liveTurn);
        }
    }

    /**
     * 异步生成会话标题：先用占位标题顶上（已落库），这里在旁路线程池里让 AI
     * 起一个短标题并覆盖，同时通过 SSE 把新标题推给前端即时刷新侧栏。
     *
     * <p>全程 best-effort：失败不影响对话，占位标题继续用。
     */
    private void scheduleTitleGeneration(String sessionId, String content, String model,
                                         TurnStreamRegistry.LiveTurn liveTurn, SseEmitter emitter) {
        titleExecutor.execute(TraceContext.wrap(() -> {
            // 标题是旁路任务：MDC 里只带 sessionId 便于检索，不与对话轮次共用 turnId
            TraceContext.setSessionId(sessionId);
            try {
                String title = orchestrationService.generateSessionTitle(content, model);
                if (title == null || title.isBlank()) {
                    return;
                }
                chatStoreService.updateTitle(sessionId, title);
                log.info("session title generated: {} -> {}", sessionId, title);
                TitlePayload payload = new TitlePayload(sessionId, title);
                // 两条通道都要发，因为前端有两条消费路径：
                //   1. 主发送链路直接读 POST 响应流（emitter）——正在等回答的这次请求
                //   2. 接续流 /turn/stream 读 TurnStreamRegistry 缓冲——切页返回、断线重连
                // 只发其中一条都会漏掉一类客户端。emitter 可能已 complete（标题回来得晚），
                // send() 内部已捕获 IO/State 异常，这里无需额外判断。
                turnStreams.publish(liveTurn, "title", toJson(payload));
                send(emitter, "title", payload);
            } catch (Exception e) {
                log.warn("async session title failed for {}: {}", sessionId, e.getMessage());
            } finally {
                TraceContext.clear();
            }
        }));
    }

    /** SSE {@code title} 事件载荷：AI 起好的会话标题（前端据此即时刷新侧栏）。 */
    public record TitlePayload(String sessionId, String title) {
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            String json = toJson(payload);
            // SseEmitter.send 不是线程安全的:同一 emitter 被对话线程(delta/step)与
            // 旁路线程(异步标题)同时写会交错、损坏事件流。所有写出统一串行化。
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            }
            // SSE 事件时间线:done/error/step 全记,delta 采样记(事件流复盘时
            // 与前端 agentApi 解析出的序列逐条对齐,竞态问题按 traceId 拉时间线)
            switch (event) {
                case "delta" -> { /* 高频,不逐条记 */ }
                case "error" -> log.warn("sse event={} payload={}", event, abbreviate(json, 300));
                case "step", "approval_required", "sources", "done" ->
                        log.info("sse event={} payload={}", event, abbreviate(json, 200));
                default -> log.debug("sse event={}", event);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
        }
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

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + " chars)";
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
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
                              Long contextWindow, Integer promptTokens, Long ttftMs,
                              /** 用户主动停止的取消轮:true;正常完成 null(缺省不序列化时前端视为 false)。
                                  接续流据此保留半截内容并标「已停止」,而非误当正常完成。 */
                              Boolean stopped) {

        /** 兼容构造器:正常完成轮(stopped 缺省)。 */
        public DonePayload(String messageId, Long durationMs, Usage usage, Integer answerChars,
                           Long contextWindow, Integer promptTokens, Long ttftMs) {
            this(messageId, durationMs, usage, answerChars, contextWindow, promptTokens, ttftMs, null);
        }

        /** Token accounting from the provider (harness: usage is a first-class done field). */
        public record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        }
    }

    /**
     * SSE error payload(异常处理系统 2026-09-12):除 message 外携带分类/错误码/
     * 提示/可重试/traceId,前端 humanizeError 直接按结构映射文案与「重试」按钮,
     * 不再字符串匹配。旧字段 message 保留(旧前端零改动)。
     */
    public record ErrorPayload(String message, String category, String errorCode,
                               String hint, Boolean retryable, String traceId) {

        /** 兼容构造器:仅 message(内部旧调用点)。 */
        public ErrorPayload(String message) {
            this(message, null, null, null, null, null);
        }

        /** 从任意异常构造:BusinessException 提取结构化字段,其余归 INTERNAL/UNAVAILABLE。 */
        public static ErrorPayload from(Throwable error) {
            String traceId = com.nora.common.logging.TraceContext.traceId();
            String message = error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
            if (error instanceof com.nora.common.exception.BusinessException be) {
                return new ErrorPayload(message, be.getCategory().name(), be.getErrorCode(),
                        be.getHint(), be.isRetryable(), traceId);
            }
            if (error instanceof IllegalArgumentException) {
                return new ErrorPayload(message, "VALIDATION", null, null, false, traceId);
            }
            return new ErrorPayload(message, "INTERNAL", null,
                    "请把 traceId 提供给管理员排障", false, traceId);
        }
    }
}
