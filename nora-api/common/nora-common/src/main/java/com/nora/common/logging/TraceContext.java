package com.nora.common.logging;

import org.slf4j.MDC;

/**
 * 链路上下文:MDC 键约定 + 取值/写值的唯一入口。
 *
 * <p>键约定(全服务统一,JSON 日志按这些键落字段):
 * <ul>
 *   <li>{@code traceId} — 一次跨服务调用链的全程 ID(gateway 或首个入口生成,
 *       经 {@code X-Nora-Trace-Id} header 在服务间传播);</li>
 *   <li>{@code sessionId} — agent 对话轮次附加维度(仅 agent-service 写入);</li>
 *   <li>{@code turnId} — agent 单轮对话 ID(仅 agent-service 写入);</li>
 *   <li>{@code userId} — 预留。</li>
 * </ul>
 *
 * <p>异步线程 {@code ThreadPoolExecutor} 不会复制 MDC:提交任务时用
 * {@link #wrap} 包装 Runnable,子线程日志才能带上 traceId。
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String SESSION_ID = "sessionId";
    public static final String TURN_ID = "turnId";
    /** 服务间链路传播 header(gateway → service → service) */
    public static final String TRACE_HEADER = "X-Nora-Trace-Id";

    private TraceContext() {
    }

    /** 取当前 traceId;无则 null。 */
    public static String traceId() {
        return MDC.get(TRACE_ID);
    }

    /** 设置 traceId(入口过滤器/异步包装时用)。 */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /** agent 对话轮次:设置 sessionId 维度。 */
    public static void setSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(SESSION_ID, sessionId);
        }
    }

    /** agent 单轮对话:设置 turnId 维度。 */
    public static void setTurnId(String turnId) {
        if (turnId != null && !turnId.isBlank()) {
            MDC.put(TURN_ID, turnId);
        }
    }

    /** 清理本线程全部链路字段(线程池复用前必须调,防止串号)。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(SESSION_ID);
        MDC.remove(TURN_ID);
    }

    /** 生成新 traceId:去横线 24 位,足够区分且日志里不占地。 */
    public static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * 包装 Runnable:进入任务时恢复提交线程的 MDC,结束时清理。
     * 提交线程自身已有 traceId 时一并带过去;没有则生成新 ID(离散任务可追溯)。
     */
    public static Runnable wrap(Runnable task) {
        var snapshot = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                } else if (MDC.get(TRACE_ID) == null) {
                    setTraceId(newTraceId());
                }
                task.run();
            } finally {
                clear();
            }
        };
    }

    /** 包装 Callable(Supplier 风格),MDC 语义同 {@link #wrap(Runnable)}。 */
    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        var snapshot = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                } else if (MDC.get(TRACE_ID) == null) {
                    setTraceId(newTraceId());
                }
                return task.call();
            } finally {
                clear();
            }
        };
    }
}
