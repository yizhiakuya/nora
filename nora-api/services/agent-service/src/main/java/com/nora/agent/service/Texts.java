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
}
