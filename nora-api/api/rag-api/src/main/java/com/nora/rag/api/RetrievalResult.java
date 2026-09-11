package com.nora.rag.api;

/**
 * A scored knowledge chunk returned by {@link RagService#search(SearchRequest)}.
 *
 * @param docId      id of the knowledge_doc row (chunk identity across fused
 *                   rankings; doc names are NOT unique — two files can share one)
 * @param docName    name of the indexed document
 * @param chunkIndex zero-based position of the chunk within the document
 * @param score      relevance score (higher is better)
 * @param snippet    text excerpt of the chunk, trimmed for prompt injection
 * @param source     storage source path/URI of the original document
 */
public record RetrievalResult(
        long docId,
        String docName,
        int chunkIndex,
        double score,
        String snippet,
        String source
) {
}
