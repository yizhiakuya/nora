package com.nora.agent.controller;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nora.agent.service.TurnStreamRegistry;
import com.nora.common.response.ApiResponse;

/**
 * 断线重连子系统(2026-09-17 从 AgentController 拆出):
 * 轮次探测 + SSE 断线续传。
 *
 * <p>为什么独立成类:这套协议有自己的不变量(seq 游标 / 回放与实况的
 * fencing / exactly-once 去重 / Last-Event-ID 解析),与消息 CRUD 是两回事。
 * 拆出后 AgentController 只剩「发起对话 + 会话管理 + 审批」,本类只关心
 * 「连接恢复」。
 *
 * <p>重连协议:每个事件带 {@code id:<seq>}(轮次内单调递增)。重连的
 * EventSource 自动带 {@code Last-Event-ID: <turnId>:<seq>};服务端回放
 * 该 seq 之后的事件,断点精确续传,不重不漏。
 */
@RestController
@RequestMapping("/api/chat")
public class TurnStreamController {

    private static final Logger log = LoggerFactory.getLogger(TurnStreamController.class);

    private static final long SSE_TIMEOUT_MS = 180_000;

    private final TurnStreamRegistry turnStreams;
    private final com.nora.agent.service.ChatStoreService chatStoreService;

    public TurnStreamController(TurnStreamRegistry turnStreams,
                                com.nora.agent.service.ChatStoreService chatStoreService) {
        this.turnStreams = turnStreams;
        this.chatStoreService = chatStoreService;
    }

    /**
     * 对话运行列表(M3-01,方案 §6.4):任务页「正在处理」聚合的数据源。
     *
     * @param status 可选逗号分隔状态过滤(如 running,awaiting_approval,cancelling);
     *               缺省 = 全部
     * @param limit  最大条数(缺省 50,封顶 200)
     */
    @GetMapping("/runs")
    public ApiResponse<java.util.List<com.nora.agent.service.ChatStoreService.RunView>> runs(
            @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(value = "limit", required = false) Integer limit) {
        java.util.List<String> statuses = (status == null || status.isBlank())
                ? null
                : java.util.Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        int bounded = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(chatStoreService.listRuns(statuses, bounded));
    }

    /**
     * 重连用的实时轮次探测:会话是否有在途轮次、已发出什么?客户端把它与
     * 本地消息尾部对比,重建流式 UI(isTyping、步骤、半截回答),
     * 无需等轮次结束。
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
     * 对实时轮次的 SSE 接入:先回放缓冲事件,再实时跟随直到
     * {@code done}/{@code error}。
     *
     * <p>重连/续传协议:每个事件带 {@code id:<seq>}(轮次内单调序号)。
     * 重连的 EventSource 发送 {@code Last-Event-ID: <turnId>:<lastSeq>};
     * 该 seq 之后的事件被回放,断开的连接精确从断点续传,不重不漏。
     *
     * <p>顺序由两个事件源的 fencing 保证:回放排空期间(在轮次上同步),
     * 实况追加阻塞;排空完成后才激活订阅。事件因此绝不互相超车或重复。
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
                // 客户端已离开
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
                sendRaw(emitter, turn.turnId, event.event(), event.json(), event.seq());
            }
        };
        // R03(2026-09-20 修复):订阅用门闩——积压在锁内快照、锁外发送;
        // 发送期间到达的实时事件先排队,排空后按序补发再切直发。此前直接
        // subscribeDraining + 锁外发积压,实时事件会超车(隔离复现 [3,1,2])。
        try {
            TurnStreamRegistry.LiveTurn.GatedSnapshot gated = turn.subscribeGated(afterSeq, forward);
            TurnStreamRegistry.Snapshot snapshot = gated.snapshot();
            if (snapshot.gap()) {
                // 游标落在滚动窗口之外:中间事件已淘汰,无法不重不漏续传。
                // 显式告知客户端(而不是静默少回放),由前端从权威消息状态恢复。
                // 注意不带 SSE id:不能让这条控制事件推进客户端游标。
                log.info("turn replay gap detected for {}: cursor={} oldest={}", sessionId, afterSeq, snapshot.oldestSeq());
                try {
                    emitter.send(SseEmitter.event().name("gap")
                            .data("{\"cursor\":" + afterSeq + ",\"oldestSeq\":" + snapshot.oldestSeq() + "}",
                                    MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException ignored) {
                    // 客户端已离开
                }
            }
            for (TurnStreamRegistry.TurnEvent e : snapshot.events()) {
                forward.accept(e);
            }
            // 积压发送完毕:补发排队实时事件,切换直发(终态不超车)
            int replayed = gated.gate().open();
            if (replayed > 0) {
                log.debug("turn {}: {} live events queued during replay, delivered in order", turn.turnId, replayed);
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

    /**
     * 发送已序列化的 JSON(TurnEvent 回放路径),不再二次 toJson。
     *
     * <p>{@code id:<turnId>:<seq>} 供 EventSource 断线续传:浏览器重连时自动
     * 带上 Last-Event-ID,服务端据此增量回放,不重不漏。
     *
     * <p><b>协议一致性(2026-09-20 修复)</b>:此前发送的 id 只有裸 seq,而
     * 解析端要求 {@code turnId:seq}——浏览器自动重连传回的纯数字永远解析失败,
     * 游标退回 0 全量回放,重复文本被再次追加。现在发送与解析使用同一格式。
     */
    private void sendRaw(SseEmitter emitter, String turnId, String event, String json, long seq) {
        try {
            emitter.send(SseEmitter.event()
                    .id(turnId + ":" + seq)
                    .name(event)
                    .data(json, MediaType.APPLICATION_JSON));
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
}
