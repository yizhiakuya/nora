package com.nora.agent.service;

/**
 * 跨组件共享的小文本工具(2026-09-17 拆分自 ChatOrchestrationService)。
 */
final class Texts {

    private Texts() {
    }

    /** 截断到 max 字符,超长加省略号;null 视作空串。 */
    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** 中断识别:JDK HttpClient 阻塞读被 interrupt 时抛 IOException(cause=InterruptedException),逐层找。 */
    static boolean isInterruption(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) return true;
            if (c.getCause() == c) break; // 自引用防环
        }
        return false;
    }
}
