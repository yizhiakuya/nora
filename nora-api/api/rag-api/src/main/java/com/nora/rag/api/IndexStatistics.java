package com.nora.rag.api;

import java.time.Instant;

/**
 * {@link RagService#getIndexStats()} 返回的索引快照。
 *
 * @param docCount       已索引文档数
 * @param chunkCount     已嵌入块数
 * @param embeddingModel 支撑索引的嵌入模型标识
 * @param indexedAt      索引最后更新时间
 */
public record IndexStatistics(
        long docCount,
        long chunkCount,
        String embeddingModel,
        Instant indexedAt
) {
}
