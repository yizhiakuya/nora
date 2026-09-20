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

    /**
     * 每轮缓冲上限:delta 很小,8000 足以覆盖带推理的长轮次。
     *
     * <p><b>滚动窗口(2026-09-20 修复)</b>:此前达到上限后**停止记录新事件**——
     * 之后掉线期间的内容永远无法回放(隔离复现:lastSeq=8002 而
     * snapshotAfter(8000) 返回 0 条)。现在满员时淘汰最旧事件、继续记录,
     * 保证「最近 8000 条」永远可回放;被淘汰的范围由 {@link Snapshot#gap()}
     * 显式告知调用方。
     */
    private static final int MAX_BUFFERED_EVENTS = 8000;

    /** 一条缓冲的 SSE 事件:轮次内单调序号 + 名称 + 预序列化 JSON。
     *  序号即 SSE 事件 id——客户端用 Last-Event-ID 精确续传,回放也因此有全序。 */
    public record TurnEvent(long seq, String event, String json) {
    }

    /**
     * 回放快照:游标之后仍可回放的事件 + 是否检测到缺口。
     *
     * @param events    严格在请求游标之后的事件(按序号升序)
     * @param gap       请求游标早于最旧缓冲事件-1:中间事件已被滚动窗口淘汰,
     *                  无法不重不漏地续传;调用方应把该情况显式告知客户端
     * @param oldestSeq 当前缓冲中最早的序号(空缓冲时为 seqCounter+1)
     */
    public record Snapshot(List<TurnEvent> events, boolean gap, long oldestSeq) {
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
            // 滚动窗口:满员淘汰最旧、继续记录新事件——旧行为「停记」会让
            // 掉线期间的全部新内容无法回放(2026-09-20 修复)。缺口由
            // snapshotAfter 的 gap 标志显式上报,前端据此从权威消息状态恢复。
            if (buffer.size() >= MAX_BUFFERED_EVENTS) {
                buffer.remove(0);
            }
            buffer.add(e);
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

        /**
         * 带缺口检测的回放快照(R04,2026-09-20 修正):请求游标早于最旧缓冲
         * 事件-1 时,中间事件已被滚动窗口淘汰——如实上报 gap。
         *
         * <p>此前只在 {@code afterSeq > 0} 时检测:初次接续(cursor=0)在缓冲
         * 已被裁剪时(oldest>1)漏报 gap,客户端把残缺回放当成完整内容。
         * 现在 cursor=0 同样检测:oldest>1 即意味着 1..oldest-1 已丢失。
         */
        public synchronized Snapshot snapshotWithGap(long afterSeq) {
            List<TurnEvent> events = snapshotAfter(afterSeq);
            if (buffer.isEmpty()) {
                // 无任何缓冲:seqCounter=0 时无缺口;有计数但缓冲空(理论不可达)按缺口处理
                return new Snapshot(events, afterSeq < seqCounter, seqCounter + 1);
            }
            long oldest = buffer.get(0).seq();
            // afterSeq=0 表示"从开头全量":oldest>1 即前段已丢(含初次接续)
            boolean gap = afterSeq < oldest - 1;
            return new Snapshot(events, gap, oldest);
        }

        public synchronized long lastSeq() {
            return seqCounter;
        }

        public synchronized void subscribe(Consumer<TurnEvent> subscriber) {
            subscribers.add(subscriber);
        }

        /**
         * 原子地快照积压( {@code afterSeq} 之后的事件)并注册订阅者
         * ——两者都在轮次监视器内完成。追加因此绝不会落进「已排空」与「已订阅」
         * 之间的缝隙:不在返回积压里的事件必然经订阅者到达。
         *
         * <p><b>R03(2026-09-20 修复)</b>:调用方随后要在锁外**发送**积压
         * (网络 I/O 不能占着轮次锁),而 append 会在锁内回调订阅者——实时
         * 事件因此可能超车积压(隔离复现 [3,1,2])。新调用方改用
         * {@link #subscribeGated} 门闩:积压发送完成前,实时事件先排队,
         * 排空后按序补发再切换直发。
         *
         * @deprecated 会超车(见上);保留仅供旧测试路径使用。
         */
        @Deprecated
        public synchronized Snapshot subscribeDraining(long afterSeq, Consumer<TurnEvent> subscriber) {
            Snapshot snapshot = snapshotWithGap(afterSeq);
            subscribers.add(subscriber);
            return snapshot;
        }

        /** 测试用别名:全量积压 + 订阅一步原子完成。 */
        public synchronized Snapshot subscribeDraining(Consumer<TurnEvent> subscriber) {
            return subscribeDraining(0, subscriber);
        }

        /**
         * R03 门闩订阅:原子地快照积压并注册「排队订阅者」。
         *
         * <p>返回的 {@link GatedSnapshot#snapshot} 是积压事件(调用方在锁外
         * 发送);这期间到达的实时事件被门闩按序排队,不直接发给消费者。
         * 积压发送完成后调用 {@link GatedSnapshot#gate()}{@code .open()}:
         * 按序补发排队事件,然后切换为直发。最终交付顺序 = 积压(升序)+
         * 排队实时(升序)+ 后续实时,不重不漏。
         *
         * <p>终态不超车:done/error 在门闩未开时同样排队,open() 后按序到达,
         * 不会先于早前事件关闭连接。
         */
        public synchronized GatedSnapshot subscribeGated(long afterSeq, Consumer<TurnEvent> consumer) {
            Snapshot snapshot = snapshotWithGap(afterSeq);
            ReplayGate gate = new ReplayGate(consumer);
            subscribers.add(gate::onEvent);
            return new GatedSnapshot(snapshot, gate);
        }

        /** 门闩订阅结果:积压快照 + 对应门闩。 */
        public record GatedSnapshot(Snapshot snapshot, ReplayGate gate) {
        }

        /** 回放门闩:见 {@link #subscribeGated}。方法线程安全(以 this 为监视器)。 */
        public static final class ReplayGate {
            private final List<TurnEvent> queued = new ArrayList<>();
            private final Consumer<TurnEvent> consumer;
            private boolean open = false;

            ReplayGate(Consumer<TurnEvent> consumer) {
                this.consumer = consumer;
            }

            /** 实时事件入口(append 在轮次锁内调用)。 */
            void onEvent(TurnEvent event) {
                synchronized (this) {
                    if (!open) {
                        queued.add(event);
                        return;
                    }
                }
                consumer.accept(event);
            }

            /**
             * 积压发送完成后调用:按序补发排队事件,然后切换直发。
             * 返回补发条数(供调用方记录)。
             */
            public int open() {
                synchronized (this) {
                    int n = queued.size();
                    for (TurnEvent e : queued) {
                        consumer.accept(e);
                    }
                    queued.clear();
                    open = true;
                    return n;
                }
            }
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
