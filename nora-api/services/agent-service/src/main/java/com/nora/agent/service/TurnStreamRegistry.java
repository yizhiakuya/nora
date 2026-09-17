package com.nora.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 进行中对话轮次的内存事件总线,按 sessionId 索引。
 *
 * <p><b>为什么存在:</b>SSE 断线不该让用户损失一整轮。浏览器离开页面时
 * 编排线程继续运行;每个事件(step / delta / sources / approval)追加到
 * 这里的有界轮次缓冲,重连的客户端可回放错过的部分再跟随实况。无人订阅
 * 时完成的轮次仍经 {@code ChatStoreService} 落库——缓冲只覆盖进行中窗口。
 *
 * <p>单用户单节点部署:重启丢缓冲可接受(轮次本身也随进程消失;DB 历史
 * 仍是权威)。
 */
@Service
public class TurnStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(TurnStreamRegistry.class);

    /** 每轮缓冲上限:delta 很小,8000 足以覆盖带推理的长轮次。 */
    private static final int MAX_BUFFERED_EVENTS = 8000;

    /** 一条缓冲的 SSE 事件:轮次内单调序号 + 名称 + 预序列化 JSON。
     *  序号即 SSE 事件 id——客户端用 Last-Event-ID 精确续传,回放也因此有全序。 */
    public record TurnEvent(long seq, String event, String json) {
    }

    /** 一个运行中轮次的实时状态。方法线程安全(以 this 为监视器)。 */
    public static final class LiveTurn {
        public final String sessionId;
        public final String content;
        public final long startedAtMs;
        /** 每个 LiveTurn 实例唯一;让 SSE 客户端把 Last-Event-ID 游标限定在
         *  本轮(上一轮的陈旧游标不得吞掉新事件)。 */
        public final String turnId = java.util.UUID.randomUUID().toString().substring(0, 8);
        private final List<TurnEvent> buffer = new ArrayList<>();
        private final List<Consumer<TurnEvent>> subscribers = new ArrayList<>();
        private long seqCounter = 0;
        private volatile boolean finished = false;

        LiveTurn(String sessionId, String content) {
            this.sessionId = sessionId;
            this.content = content;
            this.startedAtMs = System.currentTimeMillis();
        }

        public synchronized void append(String event, String json) {
            if (finished) {
                return;
            }
            TurnEvent e = new TurnEvent(++seqCounter, event, json);
            if (buffer.size() < MAX_BUFFERED_EVENTS) {
                buffer.add(e);
            }
            for (Consumer<TurnEvent> s : subscribers) {
                try {
                    s.accept(e);
                } catch (RuntimeException ex) {
                    // 单个订阅者断开不拖垮其它消费者;其 emitter 会由发送方清理
                    log.debug("turn event subscriber failed: {}", ex.getMessage());
                }
            }
        }

        /** 严格在 {@code afterSeq} 之后的事件(0 = 全量回放),按序号排序。 */
        public synchronized List<TurnEvent> snapshotAfter(long afterSeq) {
            if (afterSeq <= 0) {
                return List.copyOf(buffer);
            }
            return buffer.stream().filter(e -> e.seq() > afterSeq).toList();
        }

        public synchronized long lastSeq() {
            return seqCounter;
        }

        public synchronized void subscribe(Consumer<TurnEvent> subscriber) {
            subscribers.add(subscriber);
        }

        /**
         * 原子地快照积压( {@code afterSeq} 之后的事件)并注册 {@code subscriber}
         * ——两者都在轮次监视器内完成。追加因此绝不会落进「已排空」与「已订阅」
         * 之间的缝隙:不在返回积压里的事件必然以实况到达订阅者。调用方在锁外
         * 发送积压。
         */
        public synchronized List<TurnEvent> subscribeDraining(long afterSeq, Consumer<TurnEvent> subscriber) {
            List<TurnEvent> backlog = snapshotAfter(afterSeq);
            subscribers.add(subscriber);
            return backlog;
        }

        /** 测试用别名:全量积压 + 订阅一步原子完成。 */
        public synchronized List<TurnEvent> subscribeDraining(Consumer<TurnEvent> subscriber) {
            return subscribeDraining(0, subscriber);
        }

        public synchronized void unsubscribe(Consumer<TurnEvent> subscriber) {
            subscribers.remove(subscriber);
        }

        public synchronized void finish() {
            finished = true;
            subscribers.clear();
        }

        public boolean isFinished() {
            return finished;
        }
    }

    private final Map<String, LiveTurn> live = new ConcurrentHashMap<>();

    /** 注册开始的轮次,顶替该会话的任何陈旧条目。 */
    public LiveTurn start(String sessionId, String content) {
        LiveTurn turn = new LiveTurn(sessionId, content);
        live.put(sessionId, turn);
        return turn;
    }

    /** 标记轮次完成,并在回放窗口后从实时 map 移除。 */
    public void finish(String sessionId, LiveTurn turn) {
        turn.finish();
        // 完成事件入缓冲后再移除;短暂保留让「刚完成」的轮次还能被 /live 观测为
        // finished(前端避免把已完成轮误判为无轮次),下次同会话开始新轮自然覆盖
        live.remove(sessionId, turn);
    }

    /** 该会话的实时轮次;空闲时为 null。 */
    public LiveTurn get(String sessionId) {
        return live.get(sessionId);
    }

    /** 该会话当前是否有运行中的轮次。 */
    public boolean isRunning(String sessionId) {
        LiveTurn t = live.get(sessionId);
        return t != null && !t.finished;
    }

    /** 把一个事件广播到缓冲与全部实时订阅者。 */
    public void publish(LiveTurn turn, String event, String json) {
        turn.append(event, json);
    }
}
