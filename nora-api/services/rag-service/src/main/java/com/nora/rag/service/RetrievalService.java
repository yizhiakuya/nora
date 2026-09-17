package com.nora.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.nora.rag.api.RetrievalResult;
import com.nora.rag.config.RetrievalProperties;

/**
 * 混合检索({@code schema_rag.knowledge_chunk}):向量排名(pgvector 余弦)
 * 与关键词排名(pg_trgm {@code strict_word_similarity})经加权 RRF
 * (Reciprocal Rank Fusion)融合。
 *
 * <p>分数为 {@code 1 - cosine_distance}(越大越好),与前端
 * {@link RetrievalResult} 契约一致。
 *
 * <p><b>为什么要混合:</b>纯向量检索对精确词元召回不足——专有名词、错误码、
 * 缩写、标识符的"字面形态"比其嵌入空间邻域携带更多信号,trigram 排名能
 * 抓住这些字面重合。
 *
 * <p><b>为什么用 RRF 而不是分数混合:</b>两种分数不可通约——jina-embeddings-v3
 * 的余弦相似度聚集在窄高区间,而 trigram 相似度分布不同、跨度 0–1;
 * 直接平均会让一侧任意主导。RRF 基于排名,天然与量纲无关。
 *
 * <p>关键词排名优雅降级:若 {@code pg_trgm} 扩展不存在,查询失败只记一次
 * 日志,检索继续走纯向量,而不是整个搜索不可用。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** RRF 阻尼常数;RRF 原论文的标准取值。 */
    static final int RRF_K = 60;

    /** 向量排名:余弦相似度,最优在前。 */
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
     * 关键词排名:trigram 词相似度,最优在前。
     *
     * <p>{@code strict_word_similarity} 适合"短查询对长块",且对 CJK 无需
     * 分词器。{@code <<%} 是它的阈值形式(查询词在<em>左侧</em>);走 GIN
     * 索引,并按 {@code pg_trgm.strict_word_similarity_threshold} 预过滤——
     * 该 GUC 每连接固定为 {@link RetrievalProperties#minScore()},让配置的
     * 分数下限真正约束关键词召回(GUC 默认 0.5 否则会静默覆盖
     * {@code nora.retrieval.min-score})。
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

    /** 关键词通道确认不可用后置位,避免反复重试。 */
    private volatile boolean keywordDisabled = false;

    /**
     * @param properties 检索调参;{@code null}(测试)时用默认值
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
     * 语义 + 关键词混合检索:嵌入查询 → 两路排名 → 融合 → 丢弃低于分数下限的命中。
     *
     * @param query 自然语言查询文本
     * @param topK  最大结果数
     * @return 融合后的块,最优在前;没有命中过线时为空
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
     * 加权 RRF 融合,然后保留过分数下限的 top-K。
     *
     * <p>下限作用于每个命中的<em>原始</em>相似度(向量侧余弦/关键词侧 trigram),
     * 绝不对融合分设阈:RRF 分约为 {@code weight/(60+rank)},永远很小,
     * 对其设阈会把全部结果丢掉。融合分只决定列表顺序;上报给调用方的是
     * 原始分,前端的"低置信"区间才有意义。
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

    /** 测试接缝:两路排名融合,不做 top-K/下限裁剪。 */
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

    /** 关键词排名;禁用/查询过短/pg_trgm 缺失时为空。 */
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

    /** 块在两路排名中的同一性标识。用 docId 而非 docName:两个文件可能
     *  同名,按名融合会把它们的块混在一起(RRF 权重重复计数、一块内容丢失)。 */
    record ChunkKey(long docId, int chunkIndex) {
    }

    /** 把浮点向量渲染为 PGvector 字面量,如 "[0.1,0.2]"。 */
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

    /** 把块内容裁剪为适合提示词的片段。 */
    static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 500 ? content : content.substring(0, 500) + "…";
    }
}
