package com.nora.agent.dto;

/** 最近一次请求的输入计量；contextWindow 未配置时仅表示整理预算。 */
public record ContextUsageDto(
        int promptTokens,
        Integer outputTokens,
        int estimatedTokens,
        long contextWindow,
        boolean windowConfigured,
        int roundIndex,
        String source,
        String modelKey,
        Integer cacheReadInputTokens,
        Integer cacheCreationInputTokens
) {}
