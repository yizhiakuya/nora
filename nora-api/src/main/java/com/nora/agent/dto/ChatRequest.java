package com.nora.agent.dto;

public record ChatRequest(String content, String model, String reasoningLevel, String permissionMode,
                          Long providerId, TaskContext context, String sender, Boolean unattended,
                          String sessionTitle) {}
