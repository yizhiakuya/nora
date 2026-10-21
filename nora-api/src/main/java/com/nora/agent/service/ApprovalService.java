package com.nora.agent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.nora.agent.dto.ApprovalRequestDto;

/** 单实例审批：票据绑定会话，超时或取消按拒绝结算。重启后的 Run 由恢复流程标记中断。 */
@Service
public class ApprovalService {
    static final int TIMEOUT_SECONDS = 120;
    public record Registered(ApprovalRequestDto ticket, CompletableFuture<Boolean> future) {}
    private record Pending(String sessionId, Registered registered) {}
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public Registered registerWithFuture(String sessionId, String stepId, ApprovalRequestDto request) {
        String token = UUID.randomUUID().toString();
        ApprovalRequestDto ticket = new ApprovalRequestDto(token, stepId, request.actionType(),
                request.target(), request.summary(), request.risk(), request.detail());
        var registered = new Registered(ticket, new CompletableFuture<>());
        pending.put(token, new Pending(sessionId, registered));
        registered.future().completeOnTimeout(false, TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((ok, err) -> pending.remove(token));
        return registered;
    }

    public boolean awaitFuture(Registered registered) {
        return registered.future().exceptionally(error -> false).join();
    }

    public boolean resolve(String sessionId, String token, boolean approved) {
        Pending request = pending.get(token);
        return request != null && request.sessionId().equals(sessionId)
                && request.registered().future().complete(approved);
    }

    public List<ApprovalRequestDto> pendingFor(String sessionId) {
        return pending.values().stream().filter(p -> p.sessionId().equals(sessionId))
                .map(p -> p.registered().ticket()).toList();
    }

    public List<com.nora.agent.dto.PendingActionDto> pendingActions() {
        return pending.values().stream().filter(p -> !p.registered().future().isDone())
                .map(p -> new com.nora.agent.dto.PendingActionDto("approval", p.sessionId(),
                        p.registered().ticket().stepId(), p.registered().ticket().summary())).toList();
    }

    public void clearPending(String sessionId) {
        pending.values().stream().filter(p -> p.sessionId().equals(sessionId))
                .forEach(p -> p.registered().future().complete(false));
    }
}
