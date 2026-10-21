package com.nora.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 同一个命令进程的快照；工具返回、后台列表和历史共用此契约。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandExecutionDto(
        String executionId, String sessionId, String turnId, String stepId, String title,
        String command, String cwd, String shell, String status,
        long startedAt, Long finishedAt, long elapsedMs, int timeoutSeconds,
        long lastOutputAt, long outputChars, String output, String logTarget,
        Integer exitCode, String error) {
    public boolean running() { return "running".equals(status); }
    public CommandExecutionDto withoutOutput() {
        return new CommandExecutionDto(executionId, sessionId, turnId, stepId, title, command, cwd, shell,
                status, startedAt, finishedAt, elapsedMs, timeoutSeconds, lastOutputAt,
                outputChars, null, logTarget, exitCode, error);
    }
}
