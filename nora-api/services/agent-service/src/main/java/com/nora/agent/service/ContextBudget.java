package com.nora.agent.service;

/**
 * 每个运行独立的输入整理预算，真实输入含缓存；不限制上游输出。
 */
final class ContextBudget {

    /** 未配置模型窗口时的默认输入整理预算。 */
    static final long DEFAULT_WINDOW = 128_000L;
    /** 请求 token 触发线比例(对齐 harness:200K@167K≈83%)。 */
    static final double TRIGGER_RATIO = 0.83;
    static final double TARGET_RATIO = 0.65;
    /** 溢出恢复(上游已报超限)时的压缩目标比例。 */
    static final double RECOVERY_RATIO = 0.60;
    private final long window;
    private final boolean windowConfigured;
    private double tokenRatio = 1;

    ContextBudget(Long contextWindow) {
        windowConfigured = contextWindow != null && contextWindow > 0;
        window = windowConfigured ? contextWindow : DEFAULT_WINDOW;
    }

    long window() {
        return window;
    }

    long triggerTokens() {
        return (long) (window * TRIGGER_RATIO);
    }

    boolean windowConfigured() { return windowConfigured; }

    long targetTokens(boolean force) { return (long) (window * (force ? RECOVERY_RATIO : TARGET_RATIO)); }

    void calibrate(int estimated, Integer actualInput) {
        if (estimated > 0 && actualInput != null && actualInput > 0) tokenRatio = (double) actualInput / estimated;
    }

    int tokens(long estimated) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(estimated * tokenRatio));
    }

    long rawTokens(long actual) { return (long) Math.floor(actual / tokenRatio); }

    /** shortcut: 渠道 tokenizer 不可用时按字符估算，收到 usage 后以完整输入校准。 */
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
