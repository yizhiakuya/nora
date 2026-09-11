package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory event bus for in-flight chat turns, keyed by sessionId.
 *
 * <p><b>Why it exists:</b> an SSE disconnect must not cost the user their
 * turn. The orchestration thread keeps running when the browser navigates
 * away; every event (step / delta / sources / approval) is appended to a
 * bounded per-turn buffer here, so a reconnecting client can replay what it
 * missed and then follow live. Turns that finish with no subscriber still
 * persist through {@code ChatStoreService} — the buffer only covers the
 * in-flight window.
 *
 * <p>Single-user, single-node deployment: buffer loss on restart is
 * acceptable (the turn itself also dies with the process; DB history remains
 * authoritative).
 */
@Service
public class TurnStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(TurnStreamRegistry.class);

    /** Per-turn buffer cap: deltas are small; 8000 covers long turns with reasoning. */
    private static final int MAX_BUFFERED_EVENTS = 8000;

    /** One buffered SSE event: monotonic per-turn sequence + name + pre-serialized JSON payload.
     *  The sequence is the SSE event id — clients reconnect with Last-Event-ID to resume
     *  exactly where they left off, and it gives replay a total order. */
    public record TurnEvent(long seq, String event, String json) {
    }

    /** Live state of one running turn. Methods are thread-safe (monitor on this). */
    public static final class LiveTurn {
        public final String sessionId;
        public final String content;
        public final long startedAtMs;
        /** Unique per LiveTurn instance; lets SSE clients scope their Last-Event-ID cursor
         *  to THIS turn (a stale cursor from a previous turn must not swallow new events). */
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

        /** Events strictly after {@code afterSeq} (0 = full replay), in seq order. */
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
         * Atomically snapshots the backlog (events after {@code afterSeq}) and
         * registers {@code subscriber} — both under the turn monitor. An append
         * can therefore never fall into the gap between "drained" and
         * "subscribed": anything not in the returned backlog will reach the
         * subscriber live. Caller sends the backlog outside the lock.
         */
        public synchronized List<TurnEvent> subscribeDraining(long afterSeq, Consumer<TurnEvent> subscriber) {
            List<TurnEvent> backlog = snapshotAfter(afterSeq);
            subscribers.add(subscriber);
            return backlog;
        }

        /** Alias kept for tests: full backlog + subscribe in one atomic step. */
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

    /** Registers a starting turn, superseding any stale entry for the session. */
    public LiveTurn start(String sessionId, String content) {
        LiveTurn turn = new LiveTurn(sessionId, content);
        live.put(sessionId, turn);
        return turn;
    }

    /** Marks the turn finished and drops it from the live map after replay window. */
    public void finish(String sessionId, LiveTurn turn) {
        turn.finish();
        // 完成事件入缓冲后再移除;短暂保留让「刚完成」的轮次还能被 /live 观测为
        // finished(前端避免把已完成轮误判为无轮次),下次同会话开始新轮自然覆盖
        live.remove(sessionId, turn);
    }

    /** The session's live turn, or null when idle. */
    public LiveTurn get(String sessionId) {
        return live.get(sessionId);
    }

    /** Whether the session currently has a running turn. */
    public boolean isRunning(String sessionId) {
        LiveTurn t = live.get(sessionId);
        return t != null && !t.finished;
    }

    /** Broadcasts one event to the buffer and all live subscribers. */
    public void publish(LiveTurn turn, String event, String json) {
        turn.append(event, json);
    }
}
