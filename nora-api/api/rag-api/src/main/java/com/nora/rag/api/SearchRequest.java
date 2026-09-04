package com.nora.rag.api;

/**
 * Search parameters for {@link RagService#search(SearchRequest)}.
 *
 * @param query natural-language query text
 * @param topK  maximum number of results to return
 */
public record SearchRequest(
        String query,
        int topK
) {
}
