package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.common.redis.NoraRedis;
import com.nora.common.redis.RedisProperties;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import jakarta.annotation.PostConstruct;
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
 *
 * <p><b>Redis mirror (optional):</b> tickets are also written to Redis so
 * (a) a pending list survives an agent-service restart within the timeout
 * window, and (b) a resolve landing on any instance completes the future
 * blocked in {@code await()} via a pub/sub notification. Redis disabled or
 * unreachable degrades to the original in-process behaviour.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    static final int TIMEOUT_SECONDS = 120;

    /** Redis ticket hash: sessionId / stepId / payload(json). */
    static final String TICKET_PREFIX = "nora:approval:ticket:";
    /** Per-session token set for cross-instance pendingFor. */
    static final String SESSION_SET_PREFIX = "nora:approval:session:";
    /** Cross-instance resolution channel. */
    static final String RESOLVED_CHANNEL = "nora:approval:resolved";
    /** Ticket TTL: slightly longer than the in-process wait so a late click still resolves. */
    static final long REDIS_TTL_SECONDS = TIMEOUT_SECONDS + 30;

    record PendingRequest(String approvalToken, String sessionId, String stepId,
                          ApprovalRequestDto payload,
                          CompletableFuture<Boolean> future, Instant createdAt) {
    }

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final NoraRedis redis;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalService(ObjectMapper objectMapper, NoraRedis redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    /** Back-compat constructor (tests): Redis disabled, pure in-process behaviour. */
    public ApprovalService(ObjectMapper objectMapper) {
        this(objectMapper, new NoraRedis(RedisProperties.disabled()));
    }

    /**
     * Subscribes to the cross-instance resolution channel: a resolve handled
     * elsewhere completes the local future, releasing the blocked turn.
     */
    @PostConstruct
    void subscribeResolutions() {
        redis.pubSub().ifPresent(connection -> {
            connection.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    if (!RESOLVED_CHANNEL.equals(channel)) {
                        return;
                    }
                    try {
                        var node = objectMapper.readTree(message);
                        String token = node.path("token").asText(null);
                        boolean approved = node.path("approved").asBoolean(false);
                        PendingRequest request = token == null ? null : pending.get(token);
                        if (request != null) {
                            log.info("approval resolved on another instance: session={} approved={}",
                                    request.sessionId(), approved);
                            request.future().complete(approved);
                        }
                    } catch (Exception e) {
                        log.warn("approval resolution message ignored: {}", e.getMessage());
                    }
                }
            });
            connection.sync().subscribe(RESOLVED_CHANNEL);
            log.info("approval service: subscribed to {}", RESOLVED_CHANNEL);
        });
    }

    /** A newly registered request, including the one-shot token for the UI. */
    public ApprovalRequestDto register(String sessionId, String stepId, ApprovalRequestDto request) {
        String token = UUID.randomUUID().toString();
        ApprovalRequestDto ticket = new ApprovalRequestDto(token, request.stepId(), request.actionType(),
                request.target(), request.summary(), request.risk(), request.detail());
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(token, new PendingRequest(token, sessionId, stepId, ticket, future, Instant.now()));
        mirrorToRedis(token, sessionId, stepId, ticket);
        log.info("approval required: session={} step={} action={}", sessionId, stepId, ticket.actionType());
        future.whenComplete((ok, err) -> {
            pending.remove(token);
            clearRedisTicket(token, sessionId);
        });
        return ticket;
    }

    /** Mirrors the ticket into Redis (TTL-bounded) for restart/cross-instance visibility. */
    private void mirrorToRedis(String token, String sessionId, String stepId, ApprovalRequestDto ticket) {
        redis.call(commands -> {
            String key = TICKET_PREFIX + token;
            commands.hset(key, Map.of(
                    "sessionId", sessionId,
                    "stepId", stepId == null ? "" : stepId,
                    "payload", toJson(ticket)));
            commands.expire(key, REDIS_TTL_SECONDS);
            String setKey = SESSION_SET_PREFIX + sessionId;
            commands.sadd(setKey, token);
            commands.expire(setKey, REDIS_TTL_SECONDS);
            return null;
        });
    }

    /** Removes the Redis ticket + session-set entry after resolution. */
    private void clearRedisTicket(String token, String sessionId) {
        redis.call(commands -> {
            commands.del(TICKET_PREFIX + token);
            commands.srem(SESSION_SET_PREFIX + sessionId, token);
            return null;
        });
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
        if (request != null) {
            if (!request.sessionId().equals(sessionId)) {
                return false;
            }
            // Complete before the callback removes the entry; this closes the
            // register → SSE event → user click race where await() could observe
            // a removed token and incorrectly turn an approval into a decline.
            return request.future().complete(approved);
        }
        // 本地没有(另一实例注册的票/本实例刚重启):查 Redis 票据,存在则广播解决
        return resolveRemote(sessionId, approvalToken, approved);
    }

    /**
     * Cross-instance resolve: validates the ticket in Redis (session match),
     * deletes it, and publishes on {@value #RESOLVED_CHANNEL} so whichever
     * instance is blocked in {@code await()} completes its local future.
     */
    private boolean resolveRemote(String sessionId, String approvalToken, boolean approved) {
        Boolean exists = redis.call(commands -> {
            String key = TICKET_PREFIX + approvalToken;
            String boundSession = commands.hget(key, "sessionId");
            if (boundSession == null || !boundSession.equals(sessionId)) {
                return false;
            }
            commands.del(key);
            commands.srem(SESSION_SET_PREFIX + sessionId, approvalToken);
            return true;
        }).orElse(false);
        if (!exists) {
            return false;
        }
        redis.publish(RESOLVED_CHANNEL, "{\"token\":\"" + approvalToken + "\",\"approved\":" + approved + "}");
        log.info("approval resolved cross-instance: session={} approved={}", sessionId, approved);
        return true;
    }

    /** Pending approvals of one session (polling fallback when SSE is reconnected). */
    public List<ApprovalRequestDto> pendingFor(String sessionId) {
        List<ApprovalRequestDto> local = pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .map(PendingRequest::payload)
                .toList();
        if (!local.isEmpty()) {
            return local;
        }
        // 本实例无挂起(重启/另一实例注册):从 Redis 集合兜底读回
        return redis.call(commands -> {
            var tokens = commands.smembers(SESSION_SET_PREFIX + sessionId);
            if (tokens == null || tokens.isEmpty()) {
                return List.<ApprovalRequestDto>of();
            }
            java.util.List<ApprovalRequestDto> out = new java.util.ArrayList<>();
            for (String token : tokens) {
                String payload = commands.hget(TICKET_PREFIX + token, "payload");
                if (payload == null) {
                    commands.srem(SESSION_SET_PREFIX + sessionId, token);
                    continue;
                }
                try {
                    out.add(objectMapper.readValue(payload, ApprovalRequestDto.class));
                } catch (Exception e) {
                    log.warn("stale approval payload ignored: {}", e.getMessage());
                }
            }
            return out;
        }).orElse(List.of());
    }

    /**
     * 会话轮次被用户取消时清掉该会话全部挂起审批:以拒绝语义完成 future,
     * 让阻塞中的工具线程立刻释放(与超时自动拒绝同路径)。
     */
    public void clearPending(String sessionId) {
        pending.values().stream()
                .filter(p -> p.sessionId().equals(sessionId))
                .forEach(p -> p.future().complete(false));
        // 同步清掉 Redis 侧票据(跨实例/重启场景的残留)
        redis.call(commands -> {
            String setKey = SESSION_SET_PREFIX + sessionId;
            var tokens = commands.smembers(setKey);
            if (tokens != null) {
                for (String token : tokens) {
                    commands.del(TICKET_PREFIX + token);
                }
            }
            commands.del(setKey);
            return null;
        });
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
