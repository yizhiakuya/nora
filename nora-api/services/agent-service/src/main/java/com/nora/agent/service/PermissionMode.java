package com.nora.agent.service;

/**
 * 会话级权限模式(前端对话框三档选择,照 Claude Code 权限模式):
 * <ul>
 *   <li>{@code ASK} — 每次工具调用都请求批准</li>
 *   <li>{@code ASSIST} — 只读操作自动执行,高风险操作请求批准(默认)</li>
 *   <li>{@code FULL} — 全部自动执行(完全访问权限)</li>
 * </ul>
 */
public enum PermissionMode {
    ASK, ASSIST, FULL;

    /**
     * Parses the frontend string; unknown/null falls back to ASSIST.
     *
     * <p>例外:机对机端点 {@code POST /api/chat/agent/run} 对 blank 显式取 FULL
     * (automation 场景无人在场审批,ASSIST 会让写操作永远挂起),见 AgentController。
     */
    public static PermissionMode parse(String raw) {
        if (raw == null || raw.isBlank()) return ASSIST;
        return switch (raw.toLowerCase()) {
            case "ask" -> ASK;
            case "full", "full_access" -> FULL;
            default -> ASSIST;
        };
    }
}
