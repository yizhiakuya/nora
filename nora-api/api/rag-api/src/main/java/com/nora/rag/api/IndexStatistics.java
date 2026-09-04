package com.nora.rag.api;

import java.time.Instant;

/**
 * Index snapshot returned by {@link RagService#getIndexStats()}.
 *
 * @param docCount       number of indexed documents
 * @param chunkCount     number of embedded chunks
 * @param embeddingModel identifier of the embedding model backing the index
 * @param indexedAt      last time the index was updated
 */
public record IndexStatistics(
        long docCount,
        long chunkCount,
        String embeddingModel,
        Instant indexedAt
) {
}
