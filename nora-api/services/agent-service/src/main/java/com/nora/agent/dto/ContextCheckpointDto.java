package com.nora.agent.dto;

import java.time.LocalDateTime;

public record ContextCheckpointDto(String boundaryId, String summary, String archivePath, int beforeTokens,
                                   ContextUsageDto contextUsage, Integer summaryInputTokens,
                                   Integer summaryOutputTokens, LocalDateTime createdAt) {
}
