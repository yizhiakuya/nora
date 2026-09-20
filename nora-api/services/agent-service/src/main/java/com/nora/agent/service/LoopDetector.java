package com.nora.agent.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 循环检测器(2026-09-20,架构设计 §9.3):
 * 发现 Agent 没有取得进展,而不是简单累计调用次数。
 *
 * <p>判定规则:同参数指纹的调用,只有**结果也不变**时才累计;
 * 结果一旦变化(轮询拿到新状态、重查有进展)即重置——
 * 「有进展的重复读取」不会被误拦。累计到阈值后再来一次同样的调用,
 * 在**执行前**阻断并给模型可操作的提示。
 *
 * <p>指纹应包含标准化后的工具、动作与业务参数,排除展示标题
 * (description 字段)等无关差异;由调用方(ToolStepEmitter)负责构造。
 */
final class LoopDetector {

    /** 单次调用的重复状态:连续相同结果的次数 + 上次结果指纹。 */
    private record Entry(int count, String lastResultHash) {
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private int attempts;

    /** 本次调用是否应被阻断:此前已有 ≥ threshold 次同参数且同结果的调用。 */
    boolean shouldBlock(String fingerprint, int threshold) {
        Entry e = entries.get(fingerprint);
        return e != null && e.count() >= threshold && e.lastResultHash() != null;
    }

    /** 被阻断前的重复次数(提示文案用;仅在 shouldBlock 为 true 时有意义)。 */
    int blockCount(String fingerprint) {
        Entry e = entries.get(fingerprint);
        return e == null ? 0 : e.count();
    }

    /** 是否处于警告档(接近阈值;调用方打日志用)。 */
    boolean shouldWarn(String fingerprint, int warnThreshold) {
        Entry e = entries.get(fingerprint);
        return e != null && e.count() >= warnThreshold;
    }

    /** 记录一次调用尝试(步骤 id 的序号用;与重复判定无关)。 */
    void countAttempt() {
        attempts++;
    }

    /** 尝试总数(步骤 id 序号)。 */
    int attempts() {
        return attempts;
    }

    /**
     * 记录一次调用的结果:与上次相同则累计,不同则重置为 1。
     *
     * @param resultHash 结果指纹(内容+长度);null = 结果未知,不参与判定
     */
    void recordResult(String fingerprint, String resultHash) {
        if (resultHash == null) {
            return;
        }
        Entry e = entries.get(fingerprint);
        if (e != null && resultHash.equals(e.lastResultHash())) {
            entries.put(fingerprint, new Entry(e.count() + 1, resultHash));
        } else {
            entries.put(fingerprint, new Entry(1, resultHash));
        }
    }
}
