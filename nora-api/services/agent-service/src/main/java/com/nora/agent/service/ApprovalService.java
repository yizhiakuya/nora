package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ApprovalRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server-side approval state for high-risk tool calls (spec:
 * docs/agent-implementation-spec.md 高风险审批协议).
 *
 * <p>The model's textual "yes" never counts — approval is a one-shot token
 * created here and clicked in the UI. Pending approvals expire after
 * {@value #TIMEOUT_SECONDS}s so a paused turn cannot hang forever; expiry
 * resolves the future as declined.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    static final int TIMEOUT_SECONDS = 120;

    record PendingRequest(String approvalToken, String sessionId, String stepId,
                          ApprovalRequestDto payload,
                          CompletableFuture<Boolean> future, Instant createdAt) {
    }

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ApprovalService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A newly registered request, including the one-shot token for the UI. */
    public ApprovalRequestDto register(String sessionId, String stepId, ApprovalRequestDto request) {
        String token = UUID.randomUUID().toString();
        ApprovalRequestDto ticket = new ApprovalRequestDto(token, request.stepId(), request.actionType(),
                request.target(), request.summary(), request.risk(), request.detail());
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(token, new PendingRequest(token, sessionId, stepId, ticket, future, Instant.now()));
        log.info("approval required: session={} step={} action={}", sessionId, stepId, ticket.actionType());
        future.whenComplete((ok, err) -> pending.remove(token));
        return ticket;
    }

    /** Waits for a ticket previously registered and returns approved/declined/timeout. */
    public boolean await(String approvalToken) {
        PendingRequest request = pending.get(approvalToken);
        if (request == null) return false;
        return request.future().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.info("approval timed out: session={} step={}", request.sessionId(), request.stepId());
                    return false;
                }).join();
    }

    /**
     * Registers a pending approval and waits for its resolution.
     * Kept for service-level callers; HTTP flows should call register + await
     * so they can emit the ticket before blocking.
     */
    public CompletableFuture<Boolean> awaitApproval(String sessionId, String stepId, ApprovalRequestDto payload) {
        ApprovalRequestDto ticket = register(sessionId, stepId, payload);
        return CompletableFuture.supplyAsync(() -> await(ticket.approvalToken()));
    }

    /** Resolves a ticket only when the path session matches its bound session. */
    public boolean resolve(String sessionId, String approvalToken, boolean approved) {
        PendingRequest request = pending.get(approvalToken);
        if (request == null || !request.sessionId().equals(sessionId)) {
            return false;
        }
        // Complete before the callback removes the entry; this closes the
        // register → SSE event → user click race where await() could observe
        // a removed token and incorrectly turn an approval into a decline.
        return request.future().complete(approved);
    }

    /** Pending approvals of one session (polling fallback when SSE is reconnected). */
    public List<ApprovalRequestDto> pendingFor(String sessionId) {
        return pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .map(PendingRequest::payload)
                .toList();
    }

    /**
     * 会话轮次被用户取消时清掉该会话全部挂起审批:以拒绝语义完成 future,
     * 让阻塞中的工具线程立刻释放(与超时自动拒绝同路径)。
     */
    public void clearPending(String sessionId) {
        pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .forEach(p -> p.future().complete(false));
    }

    /** Serializes the payload for the SSE approval_required event. */
    public String toJson(ApprovalRequestDto payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
