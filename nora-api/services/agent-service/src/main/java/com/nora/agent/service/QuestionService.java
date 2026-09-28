package com.nora.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nora.agent.dto.QuestionRequestDto;

/**
 * ask_user 澄清提问的服务端等待状态(2026-09-29,修复「工具面缺提问动作」):
 * 与 {@link ApprovalService} 同款「暂停轮次等用户」机制,但等的是自由文本
 * 答案而不是批准布尔——用户作答经
 * {@code POST /api/chat/answers/{token}} 结算,future 以字符串完成;
 * 超时/取消以 null 完成,调用方按「未回答」结算(模型收到可操作提示,
 * 不猜测、不挂死)。
 *
 * <p>为什么不镜像 Redis(与审批不同):提问等待有界(默认 300s、上限 600s),
 * 且轮次线程本身是进程内的——agent-service 重启会连带把在途轮次标记
 * interrupted(见 ChatStoreService.markStaleRunsInterrupted),票据跨进程
 * 恢复没有意义。单节点部署下纯进程内行为即可。
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

    /**
     * 等待回答:超时/异常返回 null(调用方按「未回答」结算——模型收到
     * 可操作提示而不是把空串当答案)。
     */
    public String awaitFuture(Registered registered) {
        return registered.future()
                .orTimeout(registered.ticket().timeoutSeconds(), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.info("question timed out: step={}", registered.ticket().stepId());
                    return null;
                })
                .join();
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

    /**
     * 会话轮次被用户取消时清掉该会话全部挂起提问:以「未回答」(null)完成
     * future,让阻塞中的工具线程立刻释放(与超时同路径)。
     */
    public void clearPending(String sessionId) {
        pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .forEach(p -> p.future().complete(null));
    }
}
