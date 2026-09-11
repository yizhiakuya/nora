package com.nora.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval tuning settings ({@code nora.retrieval.*}).
 *
 * <p>Hybrid retrieval fuses two ranked lists — pgvector cosine similarity and
 * pg_trgm keyword similarity — via weighted Reciprocal Rank Fusion (RRF).
 * RRF is used instead of score blending because the two scores are not
 * commensurable: cosine similarity for jina-embeddings-v3 clusters in a narrow
 * high band (typically 0.6–0.9) while trigram similarity spans 0–1 with a very
 * different distribution. Rank-based fusion is scale-free and needs no
 * calibration.
 */
@ConfigurationProperties(prefix = "nora.retrieval")
public record RetrievalProperties(
        /** Drop hits below this cosine similarity (0 disables the floor). */
        Double minScore,
        /** Weight of the vector ranking in RRF fusion. */
        Integer vectorWeight,
        /** Weight of the keyword ranking in RRF fusion. */
        Integer keywordWeight,
        /** Candidate pool size per ranking before fusion. */
        Integer candidatePool,
        /** Keyword ranking is skipped when the query is shorter than this (trigrams are noise on tiny queries). */
        Integer minKeywordQueryLength
) {

    /**
     * Default cosine floor. Calibrated against jina-embeddings-v3 on this corpus:
     * related chunks score ~0.46–0.76 while unrelated queries top out at
     * ~0.31–0.42, so 0.45 sits in the gap between the two bands. Below that
     * gap, retrieval injects noise into every prompt — including greetings that
     * have no answer in the knowledge base at all.
     */
    public static final double DEFAULT_MIN_SCORE = 0.45;
    public static final int DEFAULT_VECTOR_WEIGHT = 2;
    public static final int DEFAULT_KEYWORD_WEIGHT = 1;
    public static final int DEFAULT_CANDIDATE_POOL = 20;
    public static final int DEFAULT_MIN_KEYWORD_QUERY_LENGTH = 4;

    public RetrievalProperties {
        if (minScore == null) {
            minScore = DEFAULT_MIN_SCORE;
        }
        if (vectorWeight == null || vectorWeight <= 0) {
            vectorWeight = DEFAULT_VECTOR_WEIGHT;
        }
        if (keywordWeight == null || keywordWeight < 0) {
            keywordWeight = DEFAULT_KEYWORD_WEIGHT;
        }
        if (candidatePool == null || candidatePool <= 0) {
            candidatePool = DEFAULT_CANDIDATE_POOL;
        }
        if (minKeywordQueryLength == null || minKeywordQueryLength < 0) {
            minKeywordQueryLength = DEFAULT_MIN_KEYWORD_QUERY_LENGTH;
        }
    }
}
