package com.nora.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索调参设置({@code nora.retrieval.*})。
 *
 * <p>混合检索把两个排名表——pgvector 余弦相似度与 pg_trgm 关键词相似度——
 * 经加权 RRF(Reciprocal Rank Fusion)融合。用 RRF 而非分数混合,因为两种
 * 分数不可通约:jina-embeddings-v3 的余弦聚集在窄高区间(典型 0.6–0.9),
 * 而 trigram 相似度分布迥异、跨度 0–1。基于排名的融合与量纲无关,无需校准。
 */
@ConfigurationProperties(prefix = "nora.retrieval")
public record RetrievalProperties(
        /** 丢弃低于此余弦相似度的命中(0 = 关闭下限)。 */
        Double minScore,
        /** 向量排名在 RRF 融合中的权重。 */
        Integer vectorWeight,
        /** 关键词排名在 RRF 融合中的权重。 */
        Integer keywordWeight,
        /** 融合前每路排名的候选池大小。 */
        Integer candidatePool,
        /** 查询短于此值时跳过关键词排名(微小查询上 trigram 是噪音)。 */
        Integer minKeywordQueryLength
) {

    /**
     * 默认余弦下限。在本语料上对 jina-embeddings-v3 校准:相关块约 0.46–0.76,
     * 不相关查询最高约 0.31–0.42,0.45 正好落在两带之间。低于该间隙,
     * 检索会把噪音注入每一个提示词——包括在知识库里根本没有答案的问候语。
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
