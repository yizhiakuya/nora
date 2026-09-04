package com.nora.rag.api;

/**
 * A scored knowledge chunk returned by {@link RagService#search(SearchRequest)}.
 *
 * @param docName    name of the indexed document
 * @param chunkIndex zero-based position of the chunk within the document
 * @param score      relevance score (higher is better)
 * @param snippet    text excerpt of the chunk, trimmed for prompt injection
 * @param source     storage source path/URI of the original document
 */
public record RetrievalResult(
        String docName,
        int chunkIndex,
        double score,
        String snippet,
        String source
) {
}
