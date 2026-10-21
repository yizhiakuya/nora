package com.nora.agent.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nora.agent.dto.ChatRequest;
import com.nora.common.exception.BusinessException;
import com.nora.common.exception.ErrorCategory;
import com.nora.common.logging.TraceContext;

import jakarta.annotation.PreDestroy;

/** 所有入口共用会话占用、排队、取消与持久化终态；SSE 只是可选订阅。 */
@Service
public class ChatRunService {
    private static final Logger log = LoggerFactory.getLogger(ChatRunService.class);
    private final ChatTurnRunner runner;
    private final ChatStoreService store;
    private final AppSettingStore settings;
    private final ChatInputQueueService queue;
    private final TurnStreamRegistry streams;
    private final TurnCancellation cancellation;
    private final ApprovalService approvals;
    private final QuestionService questions;
    private final TerminalService terminal;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();

    public ChatRunService(ChatTurnRunner runner, ChatStoreService store, AppSettingStore settings,
                          ChatInputQueueService queue, TurnStreamRegistry streams, TurnCancellation cancellation,
                          ApprovalService approvals, QuestionService questions, TerminalService terminal) {
        this.runner = runner;
        this.store = store;
        this.settings = settings;
        this.queue = queue;
        this.streams = streams;
        this.cancellation = cancellation;
        this.approvals = approvals;
        this.questions = questions;
        this.terminal = terminal;
    }

    public record CompactRequest(String requestId, String focus, String model, String reasoningLevel, Long providerId) {}
    public record Result(String runId, String sessionId, String status, String answer, String error) {}
    public record Handle(String runId, String sessionId, SseEmitter stream, CompletableFuture<Result> completion) {}

    public Handle start(String sessionId, ChatRequest request) {
        return submit(sessionId, request, null, false, false);
    }

    public SseEmitter stream(String sessionId, ChatRequest request, CompactRequest compact) {
        return submit(sessionId, request, compact, false, true).stream();
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    void maintain() {
        runner.retryFinalizations();
        try {
            for (String sessionId : queue.readySessions()) {
                try { submit(sessionId, null, null, true, false); }
                catch (RuntimeException e) { log.debug("queue dispatch pending for {}: {}", sessionId, e.getMessage()); }
            }
        } catch (RuntimeException e) { log.debug("queue scan unavailable: {}", e.getMessage()); }
    }

    private Handle submit(String sessionId, ChatRequest submitted, CompactRequest submittedCompact,
                          boolean queued, boolean streaming) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (!queued && (submitted == null || submitted.content() == null || submitted.content().isBlank()
                || submitted.content().length() > 8000)) throw new IllegalArgumentException("content is required and must not exceed 8000 characters");
        ActiveRun slot = new ActiveRun();
        if (active.putIfAbsent(sessionId, slot) != null) {
            if (queued) return null;
            throw turnInProgress();
        }
        ChatInputQueueService.Input input = null;
        try {
            reconcile(sessionId);
            if (!queued && !queue.view(sessionId).messages().isEmpty()) throw turnInProgress();
            String id = UUID.randomUUID().toString().substring(0, 8);
            input = queued ? queue.claim(sessionId, id) : null;
            if (queued && input == null) { active.remove(sessionId, slot); return null; }
            if (!queued) queue.pause(sessionId, false);
            ChatRequest request = input == null ? submitted : input.request();
            CompactRequest compact = input == null ? submittedCompact : "compact".equals(input.kind())
                    ? new CompactRequest(input.id().toString(), request.content().substring("/compact".length()).trim(),
                            request.model(), request.reasoningLevel(), request.providerId()) : null;
            Map<String, Object> defaults = settings.raw("agent");
            String permission = request.permissionMode() != null ? request.permissionMode()
                    : defaults == null ? null : (String) defaults.get("permissionMode");
            String reasoning = request.reasoningLevel() != null ? request.reasoningLevel()
                    : defaults == null ? null : (String) defaults.get("reasoningLevel");
            String runId = compact == null ? id : "compact-" + UUID.fromString(compact.requestId());
            SseEmitter emitter = new SseEmitter(30 * 60_000L);
            if (!streaming) emitter.complete();
            Handle handle = new Handle(runId, sessionId, emitter, new CompletableFuture<>());
            ChatInputQueueService.Input claimed = input;
            executor.execute(TraceContext.wrap(() -> {
                Result result;
                try {
                    TraceContext.setSessionId(sessionId);
                    TraceContext.setTurnId(runId);
                    synchronized (slot) {
                        if (!slot.cancelled) cancellation.begin(sessionId);
                        slot.worker = Thread.currentThread();
                    }
                    if (compact != null) {
                        result = runner.runCompactTurn(sessionId, request.content(), request.model(), reasoning,
                                request.providerId(), runId, compact.focus(), emitter);
                    } else {
                        result = runner.runChatTurn(sessionId, request, reasoning, PermissionMode.parse(permission), runId, emitter);
                    }
                } catch (RuntimeException e) {
                    log.error("run failed for {}: {}", sessionId, e.getMessage(), e);
                    result = new Result(runId, sessionId, "unknown", "", e.getMessage());
                    emitter.complete();
                } catch (Error fatal) {
                    handle.completion().completeExceptionally(fatal);
                    throw fatal;
                } finally {
                    synchronized (slot) { slot.worker = null; }
                    cancellation.clear(sessionId);
                    active.remove(sessionId, slot);
                    try {
                        if (claimed != null && queue.releaseUnstarted(claimed.id()) > 0) queue.pause(sessionId, true);
                    } catch (RuntimeException e) { log.warn("queue finalization pending for {}: {}", sessionId, e.getMessage()); }
                    TraceContext.clear();
                }
                handle.completion().complete(result);
            }));
            return handle;
        } catch (RuntimeException e) {
            active.remove(sessionId, slot);
            if (input != null) { queue.releaseUnstarted(input.id()); queue.pause(sessionId, true); }
            throw e;
        }
    }

    public boolean cancel(String sessionId) {
        queue.pause(sessionId, true);
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        active.computeIfPresent(sessionId, (id, slot) -> {
            accepted.set(true);
            synchronized (slot) {
                slot.cancelled = true;
                cancellation.request(id);
                if (slot.worker != null) slot.worker.interrupt();
            }
            approvals.clearPending(id);
            questions.clearPending(id);
            var turn = streams.get(id);
            if (turn != null) {
                turn.closeInputs();
                if (!turn.isFinished()) {
                    try { terminal.stopTurn(id, turn.turnId); }
                    catch (RuntimeException e) { log.warn("terminal cancellation pending for {}: {}", id, e.getMessage()); }
                }
            }
            try { store.markSessionRunsCancelling(id); }
            catch (RuntimeException e) { log.debug("mark cancelling failed for {}: {}", id, e.getMessage()); }
            return slot;
        });
        return accepted.get();
    }

    public <T> T withIdleSession(String sessionId, Supplier<T> mutation) {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        active.compute(sessionId, (id, slot) -> {
            if (slot != null) throw turnInProgress();
            reconcile(id);
            result.set(mutation.get());
            return null;
        });
        return result.get();
    }

    private void reconcile(String sessionId) {
        try { runner.reconcileSession(sessionId); }
        catch (RuntimeException e) { throw BusinessException.dependency("RUN_PERSISTENCE_PENDING",
                "上一轮执行结果尚未保存，正在自动恢复；暂不能开始新执行。", "保存恢复后可继续，不会自动重放工具"); }
    }

    public static BusinessException turnInProgress() {
        return new BusinessException(ErrorCategory.CONFLICT, "TURN_IN_PROGRESS",
                "该会话已有进行中的轮次，请等待完成或停止后再操作", "停止请求受理后，仍需等待后台执行退出");
    }

    @PreDestroy
    public void shutdown() {
        for (String sessionId : active.keySet()) {
            try { cancel(sessionId); }
            catch (RuntimeException e) { log.warn("shutdown cancellation failed for {}: {}", sessionId, e.getMessage()); }
        }
        executor.shutdownNow();
    }

    private static class ActiveRun {
        boolean cancelled;
        volatile Thread worker;
    }
}
