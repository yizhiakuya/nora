package com.nora.rag.service;

import com.nora.rag.api.RetrievalResult;
import com.nora.rag.config.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval over {@code schema_rag.knowledge_chunk}: a vector ranking
 * (pgvector cosine) fused with a keyword ranking (pg_trgm
 * {@code strict_word_similarity}) via weighted Reciprocal Rank Fusion.
 *
 * <p>Score is {@code 1 - cosine_distance} so higher is better, matching the
 * frontend {@link RetrievalResult} contract.
 *
 * <p><b>Why hybrid:</b> pure vector search under-recalls exact tokens — proper
 * nouns, error codes, abbreviations, identifiers — because their surface form
 * carries more signal than their neighbourhood in embedding space. A trigram
 * ranking catches those literal overlaps.
 *
 * <p><b>Why RRF instead of score blending:</b> the two scores are not
 * commensurable. Cosine similarity for jina-embeddings-v3 clusters in a narrow
 * high band while trigram similarity spans 0–1 with a different distribution;
 * averaging them lets one side dominate arbitrarily. RRF is rank-based and
 * therefore scale-free.
 *
 * <p>Keyword ranking degrades gracefully: if the {@code pg_trgm} extension is
 * absent the query fails, is logged once, and retrieval continues
 * vector-only rather than breaking search entirely.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** RRF damping constant; standard value from the original RRF paper. */
    static final int RRF_K = 60;

    /** Vector ranking: cosine similarity, best first. */
    private static final String VECTOR_SQL = """
            SELECT c.chunk_index, c.content, d.id AS doc_id, d.name, d.source,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM schema_rag.knowledge_chunk c
            JOIN schema_rag.knowledge_doc d ON d.id = c.doc_id
            WHERE c.embedding IS NOT NULL
              AND c.deleted_at IS NULL
              AND d.deleted_at IS NULL
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """;

    /**
     * Keyword ranking: trigram word similarity, best first.
     *
     * <p>{@code strict_word_similarity} suits short queries against long chunks
     * and works on CJK without a tokenizer. The {@code <<%} operator is its
     * thresholded form with the query on the <em>left</em>; it uses the GIN
     * index and pre-filters on {@code pg_trgm.strict_word_similarity_threshold}
     * — pinned to {@link RetrievalProperties#minScore()} per connection so the
     * configured floor actually governs keyword recall too (the GUC default 0.5
     * would otherwise silently override {@code nora.retrieval.min-score}).
     */
    private static final String KEYWORD_SQL = """
            SELECT c.chunk_index, c.content, d.id AS doc_id, d.name, d.source,
                   strict_word_similarity(?, c.content) AS score
            FROM schema_rag.knowledge_chunk c
            JOIN schema_rag.knowledge_doc d ON d.id = c.doc_id
            WHERE c.content IS NOT NULL
              AND c.deleted_at IS NULL
              AND d.deleted_at IS NULL
              AND ? <<% c.content
            ORDER BY strict_word_similarity(?, c.content) DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final RetrievalProperties properties;

    /** Set once the keyword path is known to be unavailable, to stop retrying. */
    private volatile boolean keywordDisabled = false;

    /**
     * @param properties retrieval tuning; {@code null} (tests) falls back to defaults
     */
    public RetrievalService(JdbcTemplate jdbcTemplate,
                            EmbeddingService embeddingService,
                            @Autowired(required = false) RetrievalProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.properties = properties != null
                ? properties : new RetrievalProperties(null, null, null, null, null);
    }

    /**
     * Hybrid semantic + keyword search: embeds the query, ranks candidates both
     * ways, fuses the rankings, then drops hits below the score floor.
     *
     * @param query natural-language query text
     * @param topK  maximum number of results
     * @return fused chunks, best first; empty when nothing clears the floor
     */
    public List<RetrievalResult> search(String query, int topK) {
        float[] vector = embeddingService.embed(query);
        String vectorLiteral = toPgVectorLiteral(vector);

        int pool = Math.max(topK, properties.candidatePool());
        List<RetrievalResult> vectorHits =
                jdbcTemplate.query(VECTOR_SQL, rowMapper(), vectorLiteral, vectorLiteral, pool);
        List<RetrievalResult> keywordHits = keywordSearch(query, pool);

        return fuse(vectorHits, keywordHits, topK);
    }

    /**
     * Weighted RRF fusion, then keep the top-K clearing the score floor.
     *
     * <p>The floor is applied to each hit's <em>original</em> similarity
     * (cosine for the vector side, trigram for the keyword side), never to the
     * fused score: RRF scores are ~{@code weight/(60+rank)} and therefore
     * always tiny, so thresholding them would drop everything. The fused score
     * orders the result list; the original score is what gets reported to the
     * caller so the frontend's "low confidence" band stays meaningful.
     */
    List<RetrievalResult> fuse(List<RetrievalResult> vectorHits,
                               List<RetrievalResult> keywordHits,
                               int topK) {
        Map<ChunkKey, Double> fused = new LinkedHashMap<>();
        Map<ChunkKey, RetrievalResult> payloads = new LinkedHashMap<>();

        accumulate(vectorHits, fused, payloads, properties.vectorWeight());
        accumulate(keywordHits, fused, payloads, properties.keywordWeight());

        double floor = properties.minScore();
        int dropped = 0;
        List<RetrievalResult> kept = new ArrayList<>();

        for (Map.Entry<ChunkKey, Double> e : sortedByFusedScore(fused)) {
            RetrievalResult hit = payloads.get(e.getKey());
            if (hit.score() < floor) {                dropped++;
                continue;
            }
            kept.add(hit);
            if (kept.size() == topK) {
                break;
            }
        }
        if (dropped > 0) {
            log.debug("retrieval floor {} dropped {} hit(s) below {}", floor, dropped, floor);
        }
        return kept;
    }

    /** Test seam: fusion of two rankings without the top-K/floor trimming. */
    List<RetrievalResult> fuse(List<RetrievalResult> vectorHits, List<RetrievalResult> keywordHits) {
        return fuse(vectorHits, keywordHits, Integer.MAX_VALUE);
    }

    private List<Map.Entry<ChunkKey, Double>> sortedByFusedScore(Map<ChunkKey, Double> fused) {
        return fused.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(b.getValue(), a.getValue()); // 融合分降序
                    if (cmp != 0) return cmp;
                    var ka = a.getKey();
                    var kb = b.getKey();
                    int byDoc = Long.compare(ka.docId(), kb.docId());
                    if (byDoc != 0) return byDoc;
                    return Integer.compare(ka.chunkIndex(), kb.chunkIndex());
                })
                .toList();
    }

    private void accumulate(List<RetrievalResult> ranked,
                            Map<ChunkKey, Double> fused,
                            Map<ChunkKey, RetrievalResult> payloads,
                            int weight) {
        if (ranked == null || weight <= 0) {
            return;
        }
        for (int i = 0; i < ranked.size(); i++) {
            RetrievalResult r = ranked.get(i);
            ChunkKey key = new ChunkKey(r.docId(), r.chunkIndex());
            fused.merge(key, weight / (double) (RRF_K + i + 1), Double::sum);

            RetrievalResult existing = payloads.get(key);
            if (existing == null) {
                payloads.put(key, r);
            } else if (r.score() > existing.score()) {
                // 两路都命中时保留更高的原始相似度,让「低置信度」判定取最优证据
                payloads.put(key, r);
            }
        }
    }

    /** Keyword ranking; empty when disabled, too short, or pg_trgm is missing. */
    private List<RetrievalResult> keywordSearch(String query, int pool) {
        if (properties.keywordWeight() <= 0 || keywordDisabled) {
            return List.of();
        }
        if (query == null || query.strip().length() < properties.minKeywordQueryLength()) {
            return List.of();
        }
        try {
            // 把 minScore 同步到 pg_trgm 的 GUC(会话级,随连接池连接存活;幂等):
            // <<% 的预过滤阈值由它决定,否则服务端默认 0.5 静默覆盖 min-score,
            // 阈值调参对关键词侧无效。set_config 走参数绑定防注入
            jdbcTemplate.queryForObject(
                    "SELECT set_config('pg_trgm.strict_word_similarity_threshold', ?, false)",
                    String.class, String.valueOf(properties.minScore()));
            return jdbcTemplate.query(KEYWORD_SQL, rowMapper(), query, query, query, pool);
        } catch (RuntimeException e) {
            // pg_trgm 缺失/未授权:降级为纯向量,不因此让检索整体失败
            keywordDisabled = true;
            log.warn("keyword retrieval unavailable ({}), falling back to vector-only: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    private RowMapper<RetrievalResult> rowMapper() {
        return (rs, rowNum) -> new RetrievalResult(
                rs.getLong("doc_id"),
                rs.getString("name"),
                rs.getInt("chunk_index"),
                rs.getDouble("score"),
                snippet(rs.getString("content")),
                rs.getString("source")
        );
    }

    /** Identity of a chunk across the two rankings. docId, not docName: two
     *  files can share a display name, and fusing by name would merge their
     *  chunks (double-counted RRF weight, one chunk's content lost). */
    record ChunkKey(long docId, int chunkIndex) {
    }

    /** Renders a float vector as a PGvector literal, e.g. "[0.1,0.2]". */
    static String toPgVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** Trims chunk content to a prompt-friendly snippet. */
    static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 500 ? content : content.substring(0, 500) + "…";
    }
}
