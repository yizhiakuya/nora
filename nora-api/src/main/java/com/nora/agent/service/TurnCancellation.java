package com.nora.agent.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 轮次取消信号(2026-09-17,修「停止生成杀不掉批量工具」实测 bug)。
 *
 * <p>背景:用户在大批量 fetch_media 下载中途点「停止生成」,后端调
 * {@code future.cancel(true)} 中断编排线程——但中断标志会在下载返回后的
 * 步骤落库/SSE 发送链路上被下游代码(JDBC/连接池等)意外消费,工具循环
 * 下一轮检查 {@code Thread.currentThread().isInterrupted()} 时已为 false,
 * 于是整轮在用户已停止的情况下继续跑了 4 个工具轮、把 10GB 下载完。
 *
 * <p>本注册表提供**不可吞**的取消信号:cancel 端点设置标志 → 编排循环与
 * 批量下载器轮询该标志(与线程中断做 OR),不再依赖中断标志的存活。
 *
 * <p>生命周期:轮次开始时 {@link #begin} 清陈旧标志;轮次收尾 finally 里
 * {@link #clear}。取消发生在两轮之间(无进行中轮次)时,标志留在 map 里等
 * 下一轮 begin 清掉,不影响后续轮次。
 */
@Component
public class TurnCancellation {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** 轮次开始:清掉上一轮遗留的取消标志(该会话重新开始,旧信号作废)。 */
    public void begin(String sessionId) {
        if (sessionId != null) {
            cancelled.remove(sessionId);
        }
    }

    /** 用户请求停止生成:置取消标志(cancel 端点调用,配合 future.cancel(true))。 */
    public void request(String sessionId) {
        if (sessionId != null) {
            cancelled.add(sessionId);
        }
    }

    /** 该会话是否有未消费的取消请求(编排循环/批量下载器轮询)。 */
    public boolean isCancelled(String sessionId) {
        return sessionId != null && cancelled.contains(sessionId);
    }

    /** 轮次收尾:清除标志。 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            cancelled.remove(sessionId);
        }
    }
}
