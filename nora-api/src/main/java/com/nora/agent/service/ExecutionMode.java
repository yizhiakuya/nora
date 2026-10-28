package com.nora.agent.service;

/** 请求与入队时冻结，运行中不再读取会话设置。 */
public final class ExecutionMode {
    private ExecutionMode() {}

    public static String parse(String value) {
        if (value == null) return "standard";
        if (!value.equals("standard") && !value.equals("ptc"))
            throw new IllegalArgumentException("executionMode 必须是 standard 或 ptc");
        return value;
    }
}
