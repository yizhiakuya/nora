package com.nora.agent.dto;

public record ChatRequest(String content, String model, String reasoningLevel, String permissionMode,
                          Long providerId, TaskContext context, String sender, Boolean unattended,
                          String sessionTitle, String executionMode) {
    public ChatRequest(String content, String model, String reasoningLevel, String permissionMode,
                       Long providerId, TaskContext context, String sender, Boolean unattended, String sessionTitle) {
        this(content, model, reasoningLevel, permissionMode, providerId, context, sender, unattended, sessionTitle, null);
    }

    public ChatRequest withExecutionMode(String mode) {
        return new ChatRequest(content, model, reasoningLevel, permissionMode, providerId, context, sender, unattended, sessionTitle, mode);
    }
}
