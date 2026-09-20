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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ApprovalRequestDto;
import com.nora.common.redis.NoraRedis;
import com.nora.common.redis.RedisProperties;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import jakarta.annotation.PostConstruct;

/**
 * 高风险工具调用的服务端审批状态(spec:
 * docs/agent-implementation-spec.md 高风险审批协议).
 *
 * <p>模型在文本里说"同意"从不作数——审批是这里创建、由用户在 UI 点击的
 * 一次性 token。挂起的审批在 {@value #TIMEOUT_SECONDS}s 后过期,被暂停的
 * 轮次不会永久挂起;过期按拒绝结算。
 *
 * <p><b>Redis 镜像(可选):</b>票据同时写入 Redis,使 (a) 挂起列表在
 * agent-service 重启后(超时窗口内)仍可见,(b) 落到任一实例的 resolve 经
 * pub/sub 通知完成阻塞在 {@code await()} 的 future。Redis 禁用或不可达时
 * 降级为原有进程内行为。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    static final int TIMEOUT_SECONDS = 120;

    /** Redis 票据哈希:sessionId / stepId / payload(json)。 */
    static final String TICKET_PREFIX = "nora:approval:ticket:";
    /** 每会话 token 集合,供跨实例 pendingFor 查询。 */
    static final String SESSION_SET_PREFIX = "nora:approval:session:";
    /** 跨实例 resolve 广播频道。 */
    static final String RESOLVED_CHANNEL = "nora:approval:resolved";
    /** 票据 TTL:略长于进程内等待,让迟到的点击仍能完成 resolve。 */
    static final long REDIS_TTL_SECONDS = TIMEOUT_SECONDS + 30;

    record PendingRequest(String approvalToken, String sessionId, String stepId,
                          ApprovalRequestDto payload,
                          CompletableFuture<Boolean> future, Instant createdAt) {
    }

    /**
     * 注册句柄:票据(给 UI)+ 等待用 future。
     *
     * <p>为什么把 future 一并返回(2026-09-20 修复):此前 await(token) 走
     * {@code pending.get(token)} 查表,而 future 完成回调会**先**把条目从表里
     * 删除——「先 resolve(true)、后 await」的时序下 await 查到 null,把已批准
     * 的操作误判为拒绝(隔离复现:resolve=true → await=false)。等待方必须持有
     * 注册时的同一份 future,而不是依赖一张可被提前清空的 Map。
     */
    public record Registered(ApprovalRequestDto ticket, CompletableFuture<Boolean> future) {
    }

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final NoraRedis redis;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalService(ObjectMapper objectMapper, NoraRedis redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    /** 兼容构造(测试):Redis 禁用,纯进程内行为。 */
    public ApprovalService(ObjectMapper objectMapper) {
        this(objectMapper, new NoraRedis(RedisProperties.disabled()));
    }

    /**
     * 订阅跨实例 resolve 频道:其他实例处理的 resolve 会完成本地 future,
     * 释放被阻塞的轮次。
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

    /** 新注册的审批请求,含给 UI 的一次性 token。 */
    public ApprovalRequestDto register(String sessionId, String stepId, ApprovalRequestDto request) {
        return registerWithFuture(sessionId, stepId, request).ticket();
    }

    /**
     * 注册审批并返回**等待句柄**(ticket + future)。
     *
     * <p>等待方必须用它返回的 future 等待结果(见 {@link Registered} 说明);
     * 不要把 ticket 再传回 {@code await(String)} 去查表——表条目在 future
     * 完成回调里先被删除,查表等待会丢结果。
     */
    public Registered registerWithFuture(String sessionId, String stepId, ApprovalRequestDto request) {
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
        return new Registered(ticket, future);
    }

    /** 把票据镜像到 Redis(有 TTL),供重启/跨实例可见。 */
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

    /** resolve 后清除 Redis 票据与会话集合条目。 */
    private void clearRedisTicket(String token, String sessionId) {
        redis.call(commands -> {
            commands.del(TICKET_PREFIX + token);
            commands.srem(SESSION_SET_PREFIX + sessionId, token);
            return null;
        });
    }

    /**
     * 等待先前注册的票据,返回 approved/declined/timeout。
     *
     * @deprecated 会因「future 先完成、条目已删」而丢结果(2026-09-20 修复项)。
     *             新调用方用 {@link #registerWithFuture} 并直接等待其 future,
     *             或 {@link #awaitFuture(Registered)}。
     */
    @Deprecated
    public boolean await(String approvalToken) {
        PendingRequest request = pending.get(approvalToken);
        if (request == null) return false;
        return awaitFuture(request.future(), request.sessionId(), request.stepId());
    }

    /** 等待注册句柄的结果(推荐路径:不查表,future 一定拿得到)。 */
    public boolean awaitFuture(Registered registered) {
        return awaitFuture(registered.future(), null, null);
    }

    /** 等待 future 结算:超时/异常按拒绝。 */
    private boolean awaitFuture(CompletableFuture<Boolean> future, String sessionId, String stepId) {
        return future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.info("approval timed out: session={} step={}", sessionId, stepId);
                    return false;
                }).join();
    }

    /**
     * 注册挂起审批并等待其结算。供服务层调用方使用;HTTP 流程应分两步
     * (register + await),以便在阻塞前先把 token 发出去。
     */
    public CompletableFuture<Boolean> awaitApproval(String sessionId, String stepId, ApprovalRequestDto payload) {
        Registered registered = registerWithFuture(sessionId, stepId, payload);
        return CompletableFuture.supplyAsync(() -> awaitFuture(registered));
    }

    /** 仅当路径 session 与票据绑定的 session 一致时才结算。 */
    public boolean resolve(String sessionId, String approvalToken, boolean approved) {
        PendingRequest request = pending.get(approvalToken);
        if (request != null) {
            if (!request.sessionId().equals(sessionId)) {
                return false;
            }
            // 在回调移除条目前先 complete:闭合「register → SSE 事件 → 用户点击」
            // 的竞态——await() 可能观察到已被移除的 token 而把批准误判为拒绝。
            return request.future().complete(approved);
        }
        // 本地没有(另一实例注册的票/本实例刚重启):查 Redis 票据,存在则广播解决
        return resolveRemote(sessionId, approvalToken, approved);
    }

    /**
     * 跨实例 resolve:在 Redis 校验票据(session 匹配)→ 删除 → 在
     * {@value #RESOLVED_CHANNEL} 发布,让阻塞在 {@code await()} 的那个实例
     * 完成本地 future。
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

    /** 某会话的挂起审批(SSE 重连时的轮询兜底)。 */
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

    /** 为 SSE approval_required 事件序列化 payload。 */
    public String toJson(ApprovalRequestDto payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
