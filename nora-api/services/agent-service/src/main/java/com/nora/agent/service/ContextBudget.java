package com.nora.agent.service;

/**
 * 单轮对话的上下文预算(系统性上下文管理的度量核心,设计见
 * docs/context-management-design.md)。
 *
 * <p>分层预算(对齐 harness 调研 docs/harness-tool-calling-research-2026-09-05.md):
 * <ul>
 *   <li>窗口来源:设置页 per-model contextWindow,未配置回落 {@link #DEFAULT_WINDOW}</li>
 *   <li>触发线:窗口的 83%(业界数值:200K 窗口在 ~167K 触发压缩)</li>
 *   <li>输出预留:min(16K, 窗口 10%),留给本轮回答与推理 token</li>
 *   <li>历史预算:触发线 - 输出预留 - 固定开销(system/反思/新用户消息)</li>
 * </ul>
 *
 * <p>token 估算是 CJK 感知启发式(中文≈1 token/字,ASCII≈4 字符/token),
 * 供历史裁剪、轮内压缩、工具结果预算统一使用;真实计量以 done.usage
 * 下发给前端展示,不反向参与本类决策。
 */
final class ContextBudget {

    /** 未配置 contextWindow 时的默认窗口(tokens)。 */
    static final long DEFAULT_WINDOW = 128_000L;
    /** 请求 token 触发线比例(对齐 harness:200K@167K≈83%)。 */
    static final double TRIGGER_RATIO = 0.83;
    /** 溢出恢复(上游已报超限)时的压缩目标比例。 */
    static final double RECOVERY_RATIO = 0.60;
    private static final double OUTPUT_RESERVE_RATIO = 0.10;
    private static final long OUTPUT_RESERVE_CAP = 16_000L;

    private final long window;
    private final long triggerTokens;
    private final long outputReserve;

    ContextBudget(Long contextWindow) {
        this.window = contextWindow != null && contextWindow > 0 ? contextWindow : DEFAULT_WINDOW;
        this.triggerTokens = (long) (window * TRIGGER_RATIO);
        this.outputReserve = Math.min(OUTPUT_RESERVE_CAP, (long) (window * OUTPUT_RESERVE_RATIO));
    }

    long window() {
        return window;
    }

    long triggerTokens() {
        return triggerTokens;
    }

    long outputReserve() {
        return outputReserve;
    }

    /** 历史消息可用 token(不含 system/反思/本轮用户消息等固定开销)。 */
    long historyBudgetTokens(long fixedCost) {
        return Math.max(2_000L, triggerTokens - outputReserve - fixedCost);
    }

    /**
     * CJK 感知 token 估算:CJK≈1 token/字,ASCII≈1 token/4 字符,其余≈1/2;
     * 末尾 +4 为每条消息的封装开销。宁高勿低(预算型估算偏保守)。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, ascii = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) cjk++;
            else if (c < 0x80) ascii++;
            else other++;
        }
        return cjk + other / 2 + ascii / 4 + 4;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)    // CJK 统一表意
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF) // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF) // 韩文
                || (c >= 0x3000 && c <= 0x303F) // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角
    }
}
