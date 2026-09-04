package com.nora.rag.api;

import java.util.List;

/**
 * Dubbo contract for knowledge retrieval, exposed by rag-service and
 * consumed by agent-service (architecture-v2.md section 5.1).
 *
 * <p>Implementations register with Nacos; consumers inject via
 * {@code @DubboReference}.
 */
public interface RagService {

    /**
     * Semantic search over the knowledge index.
     *
     * @param request query text and result size
     * @return scored chunks ordered by relevance (best first)
     */
    List<RetrievalResult> search(SearchRequest request);

    /**
     * Snapshot of the current index statistics.
     *
     * @return document/chunk counts plus the embedding model in use
     */
    IndexStatistics getIndexStats();
}
