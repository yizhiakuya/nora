package com.nora.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nora.agent.dto.QuestionRequestDto;

/**
 * ask_user 澄清提问的服务端等待状态(2026-09-29,修复「工具面缺提问动作」):
 * 与 {@link ApprovalService} 同款「暂停轮次等用户」机制,但等的是自由文本
 * 答案而不是批准布尔——用户作答经
 * {@code POST /api/chat/answers/{token}} 结算,future 以字符串完成;
 * 超时返回未回答；取消与线程中断结束轮次，不能继续当作超时执行。
 *
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    /** 默认等待上限(秒):用户可能在忙,给足作答窗口。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    /** 硬上限:再长不如让用户稍后重新发起。 */
    static final int MAX_TIMEOUT_SECONDS = 600;
    /** 下限:防止模型传 0 导致必然超时。 */
    static final int MIN_TIMEOUT_SECONDS = 5;

    record Pending(String token, String sessionId, String stepId,
                   QuestionRequestDto payload,
                   CompletableFuture<String> future, Instant createdAt) {
    }

    /**
     * 注册句柄:票据(给 UI)+ 等待用 future。
     * 与审批同款教训:等待方必须持有注册时的同一份 future,不依赖可被提前清空的表。
     */
    public record Registered(QuestionRequestDto ticket, CompletableFuture<String> future) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * 注册一个提问并返回等待句柄。
     *
     * @param timeoutSeconds 模型可选的等待秒数(缺省 {@value #DEFAULT_TIMEOUT_SECONDS},
     *                       收敛到 [{@value #MIN_TIMEOUT_SECONDS}, {@value #MAX_TIMEOUT_SECONDS}])
     */
    public Registered registerWithFuture(String sessionId, String stepId, String question,
                                         List<String> options, Integer timeoutSeconds) {
        String token = UUID.randomUUID().toString();
        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS
                : Math.min(Math.max(timeoutSeconds, MIN_TIMEOUT_SECONDS), MAX_TIMEOUT_SECONDS);
        QuestionRequestDto ticket = new QuestionRequestDto(token, stepId, question, options, timeout);
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(token, new Pending(token, sessionId, stepId, ticket, future, Instant.now()));
        log.info("question required: session={} step={} options={}", sessionId, stepId,
                options == null ? 0 : options.size());
        future.whenComplete((answer, err) -> pending.remove(token));
        return new Registered(ticket, future);
    }

    public record Answer(String text, boolean superseded) { }

    /** 插入输入只结束提问等待，不充当答案或取消整轮。 */
    public Answer awaitFuture(Registered registered, CompletableFuture<Void> inputAvailable) {
        try {
            Object answer = CompletableFuture.anyOf(registered.future(), inputAvailable)
                    .orTimeout(registered.ticket().timeoutSeconds(), TimeUnit.SECONDS).get();
            return new Answer((String) answer, answer == null);
        } catch (InterruptedException e) {
            registered.future().cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("question interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) {
                    log.info("question timed out: step={}", registered.ticket().stepId());
                    return new Answer(null, false);
            }
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Question failed", e.getCause());
        } finally {
            registered.future().cancel(false);
        }
    }

    /** 仅当路径 session 与票据绑定的 session 一致时才结算;空白答案拒绝。 */
    public boolean resolve(String sessionId, String questionToken, String answer) {
        Pending p = pending.get(questionToken);
        if (p == null || !p.sessionId().equals(sessionId)) {
            return false;
        }
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return p.future().complete(answer.trim());
    }

    /** 某会话的挂起提问(SSE 重连兜底 / 手工排查)。 */
    public List<QuestionRequestDto> pendingFor(String sessionId) {
        return pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .map(Pending::payload)
                .toList();
    }

    public List<com.nora.agent.dto.PendingActionDto> pendingActions() {
        Instant now = Instant.now();
        return pending.values().stream().filter(p -> !p.future().isDone()
                        && p.createdAt().plusSeconds(p.payload().timeoutSeconds()).isAfter(now))
                .map(p -> new com.nora.agent.dto.PendingActionDto("question", p.sessionId(),
                        p.stepId(), p.payload().question())).toList();
    }

    /** 取消该会话的全部等待，立即释放工具线程。 */
    public void clearPending(String sessionId) {
        pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .forEach(p -> p.future().cancel(true));
    }
}
