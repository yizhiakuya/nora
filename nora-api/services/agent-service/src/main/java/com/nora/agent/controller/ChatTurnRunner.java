package com.nora.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import com.nora.agent.service.ChatOrchestrationService;
import com.nora.agent.service.ChatStoreService;
import com.nora.agent.service.PermissionMode;
import com.nora.agent.service.TurnStreamRegistry;
import com.nora.common.logging.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一轮对话的执行引擎(2026-09-17 从 AgentController 拆出,复杂度审计建议 #2):
 * 编排调用 + 事件双写(emitter 直发 + TurnStreamRegistry 缓冲)+ 步骤/推理
 * 落库 + 取消/失败收尾 + 异步标题生成 + SSE 发送辅助与载荷类型。
 * 纯机械平移,行为与拆分前逐行一致。
 */
class ChatTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnRunner.class);

    private final ChatOrchestrationService orchestrationService;
    private final ChatStoreService chatStoreService;
    private final TurnStreamRegistry turnStreams;
    private final ObjectMapper objectMapper;

    /**
     * 会话标题生成专用线程池。
     *
     * <p>独立于对话轮次执行线程：起标题是「锦上添花」的旁路任务，不能与
     * 对话轮次争抢，也不能被「停止生成」（中断对话线程上的 Future）连带取消
     * ——用户停掉回答后，标题仍应正常生成。
     */
    private final ExecutorService titleExecutor = Executors.newCachedThreadPool();

    ChatTurnRunner(ChatOrchestrationService orchestrationService,
                   ChatStoreService chatStoreService,
                   TurnStreamRegistry turnStreams,
                   ObjectMapper objectMapper) {
        this.orchestrationService = orchestrationService;
        this.chatStoreService = chatStoreService;
        this.turnStreams = turnStreams;
        this.objectMapper = objectMapper;
    }

    void runChatTurn(String sessionId, String content, String model, String reasoningLevel,
                         PermissionMode permissionMode, Long providerId, SseEmitter emitter) {
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
                scheduleTitleGeneration(sessionId, content, model, providerId, liveTurn, emitter);
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
                    providerId,
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
                        public void upstreamRequestStarted() {
                            // 请求即将发出:把推理计时锚点推到此刻——首轮无前置 step 时
                            // 起点不再落到编排之前,会话加载/RAG 检索/提示词装配不计入
                            // 「已深度思考」时长(与"按轮真实思考时长"口径一致)。
                            lastEventMs[0] = System.currentTimeMillis();
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
    private void scheduleTitleGeneration(String sessionId, String content, String model, Long providerId,
                                         TurnStreamRegistry.LiveTurn liveTurn, SseEmitter emitter) {
        titleExecutor.execute(TraceContext.wrap(() -> {
            // 标题是旁路任务：MDC 里只带 sessionId 便于检索，不与对话轮次共用 turnId
            TraceContext.setSessionId(sessionId);
            try {
                String title = orchestrationService.generateSessionTitle(content, model, providerId);
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