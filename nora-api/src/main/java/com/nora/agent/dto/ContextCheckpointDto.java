package com.nora.agent.dto;

import java.time.LocalDateTime;

public record ContextCheckpointDto(String boundaryId, String summary, String archivePath, int beforeTokens,
                                   ContextUsageDto contextUsage, Integer summaryInputTokens,
                                   Integer summaryOutputTokens, LocalDateTime createdAt,
                                   String activeRunId, Integer coveredThroughRound) {
    public ContextCheckpointDto(String boundaryId, String summary, String archivePath, int beforeTokens,
                                ContextUsageDto contextUsage, Integer summaryInputTokens,
                                Integer summaryOutputTokens, LocalDateTime createdAt) {
        this(boundaryId, summary, archivePath, beforeTokens, contextUsage, summaryInputTokens, summaryOutputTokens, createdAt, null, null);
    }
}
