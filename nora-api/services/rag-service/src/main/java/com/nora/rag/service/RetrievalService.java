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

import com.nora.rag.api.RetrievalOutcome;
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
 * <p><b>通道独立与降级可见(2026-09-22,知识库优化阶段 A):</b>嵌入不可用时
 * 关键词仍尝试执行;部分通道失败返回 {@code degraded},全部失败返回
 * {@code unavailable}——不再把「检索坏了」折叠成「资料里没有」。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** RRF 阻尼常数;RRF 原论文的标准取值。 */
    static final int RRF_K = 60;

    /** 预览摘录的目标长度(围绕命中位置截取)。 */
    static final int SNIPPET_CHARS = 500;

    /**
     * 向量排名:余弦相似度,最优在前。
     *
     * <p>只检索**已发布**(d.status='indexed')、**启用**(d.enabled)且存活的
     * 文档——构建中/失败/停用的版本行不参与召回(阶段 A/B)。
     *
     * <p>{@code %SCOPE%} 是范围过滤占位(资料库/指定文档;空串=全库),
     * 由 {@link #scopeClause} 生成,条件同时进入向量与关键词候选查询。
     */
    private static final String VECTOR_SQL = """
            SELECT c.id AS chunk_id, c.chunk_index, c.content, d.id AS doc_id, d.name, d.source,
                   c.parent_index, c.parent_content,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM schema_rag.knowledge_chunk c
            JOIN schema_rag.knowledge_doc d ON d.id = c.doc_id
            WHERE c.embedding IS NOT NULL
              AND c.deleted_at IS NULL
              AND d.deleted_at IS NULL
              AND d.status = 'indexed'
              AND d.enabled = true
              %SCOPE%
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
            SELECT c.id AS chunk_id, c.chunk_index, c.content, d.id AS doc_id, d.name, d.source,
                   c.parent_index, c.parent_content,
                   strict_word_similarity(?, c.content) AS score
            FROM schema_rag.knowledge_chunk c
            JOIN schema_rag.knowledge_doc d ON d.id = c.doc_id
            WHERE c.content IS NOT NULL
              AND c.deleted_at IS NULL
              AND d.deleted_at IS NULL
              AND d.status = 'indexed'
              AND d.enabled = true
              AND ? <<% c.content
              %SCOPE%
            ORDER BY strict_word_similarity(?, c.content) DESC
            LIMIT ?
            """;

    /**
     * 检索范围(阶段 B,方案 §6.1):资料库 / 指定文档。
     *
     * <p>范围条件**同时**进入向量与关键词候选查询;范围内无结果就返回无结果,
     * 不自动扩大到全部资料。
     *
     * @param baseId 资料库 id;null = 不限库
     * @param docIds 指定文档 id 列表;null/空 = 不限文档
     */
    public record RetrievalScope(Long baseId, java.util.List<Long> docIds) {
        public static final RetrievalScope ALL = new RetrievalScope(null, null);

        public boolean unrestricted() {
            return baseId == null && (docIds == null || docIds.isEmpty());
        }
    }

    /** 生成范围过滤子句与参数(按占位顺序)。 */
    private static String scopeClause(RetrievalScope scope) {
        if (scope == null || scope.unrestricted()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (scope.baseId() != null) {
            sb.append("AND d.base_id = ?\n");
        }
        if (scope.docIds() != null && !scope.docIds().isEmpty()) {
            sb.append("AND d.id IN (")
              .append(String.join(",", scope.docIds().stream().map(i -> "?").toList()))
              .append(")\n");
        }
        return sb.toString();
    }

    /** 范围参数(与 {@link #scopeClause} 的占位顺序一致)。 */
    private static Object[] scopeArgs(RetrievalScope scope) {
        if (scope == null || scope.unrestricted()) {
            return new Object[0];
        }
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (scope.baseId() != null) {
            args.add(scope.baseId());
        }
        if (scope.docIds() != null) {
            args.addAll(scope.docIds());
        }
        return args.toArray();
    }

    /** 组装"前段参数 + 范围参数 + 后段参数"。 */
    private static Object[] withScope(Object[] prefix, RetrievalScope scope, Object[] suffix) {
        Object[] scopeArgs = scopeArgs(scope);
        Object[] out = new Object[prefix.length + scopeArgs.length + suffix.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(scopeArgs, 0, out, prefix.length, scopeArgs.length);
        System.arraycopy(suffix, 0, out, prefix.length + scopeArgs.length, suffix.length);
        return out;
    }

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
     * 语义 + 关键词混合检索:两路独立召回 → 融合 → 丢弃低于分数下限的命中。
     *
     * <p>兼容入口(旧调用方):通道状态折叠为列表;需要降级可见的调用方
     * 用 {@link #searchWithStatus}。
     *
     * @param query 自然语言查询文本
     * @param topK  最大结果数
     * @return 融合后的块,最优在前;没有命中过线时为空
     */
    public List<RetrievalResult> search(String query, int topK) {
        return searchWithStatus(query, topK).results();
    }

    /**
     * 带通道状态的检索(2026-09-22,阶段 A):区分 ok/no_match/degraded/unavailable。
     *
     * <p>通道独立:向量(嵌入)失败不阻断关键词;关键词失败(或 pg_trgm 缺失)
     * 只降级不抛错。只有**全部通道失败**才返回 unavailable。
     */
    public RetrievalOutcome searchWithStatus(String query, int topK) {
        return searchWithStatus(query, topK, RetrievalScope.ALL);
    }

    /**
     * 带范围过滤的检索(阶段 B,方案 §6.1):范围条件同时进入两路候选查询;
     * 范围内无结果就返回无结果,不自动扩大到全部资料。
     */
    public RetrievalOutcome searchWithStatus(String query, int topK, RetrievalScope scope) {
        long startedAt = System.currentTimeMillis();
        int pool = Math.max(topK, properties.candidatePool());

        // 通道 1:向量(嵌入可能失败——配错 key/网络不通/超限)
        List<RetrievalResult> vectorHits = null;
        RetrievalOutcome.ChannelStatus vectorStatus;
        try {
            float[] vector = embeddingService.embed(query);
            String vectorLiteral = toPgVectorLiteral(vector);
            String sql = VECTOR_SQL.replace("%SCOPE%", scopeClause(scope));
            vectorHits = jdbcTemplate.query(sql, rowMapper(),
                    withScope(new Object[]{vectorLiteral}, scope, new Object[]{vectorLiteral, pool}));
            vectorStatus = RetrievalOutcome.ChannelStatus.up();
        } catch (Exception e) {
            log.warn("vector channel failed for query '{}': {}", abbreviate(query), e.getMessage());
            vectorStatus = RetrievalOutcome.ChannelStatus.down(describeChannelError(e));
        }

        // 通道 2:关键词(独立执行——嵌入失败也照常尝试)
        List<RetrievalResult> keywordHits = keywordSearch(query, pool, scope);
        RetrievalOutcome.ChannelStatus keywordStatus = keywordHits != null
                ? RetrievalOutcome.ChannelStatus.up()
                : RetrievalOutcome.ChannelStatus.down(
                        keywordDisabledReason != null ? keywordDisabledReason : "关键词通道不可用");
        if (keywordHits == null) {
            keywordHits = List.of();
        }

        List<RetrievalResult> fused = fuse(vectorHits == null ? List.of() : vectorHits,
                keywordHits, topK);
        // 标注命中通道与双通道原始分(预览围绕命中位置截取)
        fused = annotateChannels(fused, vectorHits == null ? List.of() : vectorHits, keywordHits, query);
        // 父子合并(阶段 B):子块命中 → 返回父块上下文,同一父块的多个子命中合并
        fused = mergeParentContext(fused);
        String status = RetrievalOutcome.statusOf(fused, vectorStatus, keywordStatus);
        // 检索记录(阶段 B,方案 §6.3):供「为什么找不到」回溯
        logRetrieval(query, topK, scope, status, fused, System.currentTimeMillis() - startedAt);
        return new RetrievalOutcome(status, fused, vectorStatus, keywordStatus);
    }

    /**
     * 父子合并(阶段 B,方案 §5.2):命中子块时把 {@code content} 换为
     * **父块完整正文**(模型上下文),记录实际命中的子块位置;
     * 同一父块的多个子命中合并为一条(以最佳子命中排序),避免子块多的
     * 长文档靠数量占满结果。
     */
    List<RetrievalResult> mergeParentContext(List<RetrievalResult> hits) {
        java.util.LinkedHashMap<String, RetrievalResult> merged = new java.util.LinkedHashMap<>();
        for (RetrievalResult r : hits) {
            String parentContent = r.parentContent();
            if (parentContent == null || parentContent.isBlank()) {
                // 非父子模式(或旧数据):按 (docId, chunkIndex) 直接保留
                merged.putIfAbsent(r.docId() + ":" + r.chunkIndex(), r);
                continue;
            }
            String key = r.docId() + ":p" + r.parentIndex();
            RetrievalResult existing = merged.get(key);
            if (existing == null) {
                // 首个子命中:content 换为父块正文(超长按段落裁选),标注命中位置
                String parent = parentContent.length() <= ChunkingService.PARENT_MAX_CHARS
                        ? parentContent
                        : parentContent.substring(0, ChunkingService.PARENT_MAX_CHARS)
                                + "\n…[父章节过长已截断,共 " + parentContent.length() + " 字符]";
                merged.put(key, new RetrievalResult(
                        r.docId(), r.docName(), r.chunkIndex(), r.chunkId(),
                        r.score(), r.vectorScore(), r.keywordScore(), r.matchChannel(),
                        r.snippet(), parent, r.source()));
            }
            // 后续同父子命中:保留首条(最佳子命中已因融合排序在前),不重复
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /**
     * 记录一次检索(阶段 B;尽力而为,失败静默——记录不是检索的一部分)。
     * detail 里存结果标识(文档/块/通道/分),正文不入库(方案 §4)。
     */
    private void logRetrieval(String query, int topK, RetrievalScope scope,
                              String status, List<RetrievalResult> results, long durationMs) {
        try {
            StringBuilder detail = new StringBuilder(256);
            for (RetrievalResult r : results) {
                if (detail.length() > 0) {
                    detail.append(',');
                }
                detail.append("{\"docId\":").append(r.docId())
                      .append(",\"chunkIndex\":").append(r.chunkIndex())
                      .append(",\"chunkId\":").append(r.chunkId() == null ? "null" : r.chunkId())
                      .append(",\"score\":").append(String.format(java.util.Locale.ROOT, "%.4f", r.score()))
                      .append(",\"channel\":\"").append(r.matchChannel() == null ? "" : r.matchChannel())
                      .append("\"}");
            }
            String scopeJson = scope == null || scope.unrestricted() ? null
                    : "{\"baseId\":" + scope.baseId() + ",\"docIds\":" + scope.docIds() + "}";
            jdbcTemplate.update(
                    "INSERT INTO schema_rag.retrieval_log (query, top_k, scope_json, status, result_count, duration_ms, detail_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    query.length() > 2000 ? query.substring(0, 2000) : query,
                    topK, scopeJson, status, results.size(), durationMs,
                    "[" + detail + "]");
        } catch (Exception e) {
            log.debug("retrieval log write failed (ignored): {}", e.getMessage());
        }
    }

    /** 关键词通道的降级原因(置位后保留首个原因)。 */
    private volatile String keywordDisabledReason;

    private static String describeChannelError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private static String abbreviate(String query) {
        return query == null ? "" : (query.length() > 60 ? query.substring(0, 60) + "…" : query);
    }

    /**
     * 标注每个命中的来源通道与双通道原始分:
     * matchChannel = vector / keyword / both;分数保留两路各自的值。
     * 预览围绕查询词在正文中的首次出现位置截取(命中位置可见)。
     */
    private List<RetrievalResult> annotateChannels(List<RetrievalResult> fused,
                                                   List<RetrievalResult> vectorHits,
                                                   List<RetrievalResult> keywordHits,
                                                   String query) {
        Map<ChunkKey, Double> vectorScores = new LinkedHashMap<>();
        for (RetrievalResult r : vectorHits) {
            vectorScores.put(new ChunkKey(r.docId(), r.chunkIndex()), r.score());
        }
        Map<ChunkKey, Double> keywordScores = new LinkedHashMap<>();
        for (RetrievalResult r : keywordHits) {
            keywordScores.put(new ChunkKey(r.docId(), r.chunkIndex()), r.score());
        }
        List<RetrievalResult> out = new ArrayList<>(fused.size());
        for (RetrievalResult r : fused) {
            ChunkKey key = new ChunkKey(r.docId(), r.chunkIndex());
            Double v = vectorScores.get(key);
            Double k = keywordScores.get(key);
            String channel = v != null && k != null ? "both" : v != null ? "vector" : "keyword";
            out.add(new RetrievalResult(
                    r.docId(), r.docName(), r.chunkIndex(), r.chunkId(),
                    r.score(), v, k, channel,
                    snippetAround(r.content(), query),
                    r.content(), r.source(),
                    r.parentIndex(), r.parentContent()));
        }
        return out;
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
            if (hit.score() < floor) {
                dropped++;
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

    /**
     * 关键词排名;禁用/查询过短/pg_trgm 缺失时为空。
     *
     * <p>2026-09-22(阶段 A):失败**不永久停用**——记一次原因与时间戳,
     * 60 秒冷却后允许重试(此前一旦失败即 `keywordDisabled=true` 到服务重启,
     * 短暂故障会永久降级到纯向量)。返回 null 表示通道失败(与"正常无命中"
     * 的空列表区分)。
     */
    private List<RetrievalResult> keywordSearch(String query, int pool, RetrievalScope scope) {
        if (properties.keywordWeight() <= 0) {
            return List.of();
        }
        if (keywordDisabled && !keywordCooldownElapsed()) {
            return null;
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
            String sql = KEYWORD_SQL.replace("%SCOPE%", scopeClause(scope));
            List<RetrievalResult> hits = jdbcTemplate.query(sql, rowMapper(),
                    withScope(new Object[]{query, query}, scope, new Object[]{query, pool}));
            // 成功一次即清除降级标记(通道恢复)
            if (keywordDisabled) {
                log.info("keyword retrieval recovered");
                keywordDisabled = false;
                keywordDisabledReason = null;
            }
            return hits;
        } catch (RuntimeException e) {
            keywordDisabled = true;
            keywordDisabledReason = describeChannelError(e);
            keywordDisabledAt = System.currentTimeMillis();
            log.warn("keyword retrieval unavailable ({}), falling back to vector-only: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 关键词通道降级时间戳(冷却重试用)。 */
    private volatile long keywordDisabledAt = 0;
    /** 降级冷却:60 秒后允许重试(短暂故障不永久降级)。 */
    private static final long KEYWORD_COOLDOWN_MS = 60_000;

    private boolean keywordCooldownElapsed() {
        return System.currentTimeMillis() - keywordDisabledAt > KEYWORD_COOLDOWN_MS;
    }

    /** 关键词通道当前状态(供降级可见性上报)。 */
    String keywordDisabledReason() {
        return keywordDisabledReason;
    }

    private RowMapper<RetrievalResult> rowMapper() {
        return (rs, rowNum) -> {
            String content = rs.getString("content");
            long chunkId;
            try {
                chunkId = rs.getLong("chunk_id");
            } catch (Exception e) {
                chunkId = 0; // 旧查询形态无此列(测试桩)
            }
            Integer parentIndex = null;
            String parentContent = null;
            try {
                int pi = rs.getInt("parent_index");
                parentIndex = rs.wasNull() ? null : pi;
                parentContent = rs.getString("parent_content");
            } catch (Exception e) {
                // 旧查询形态无父子列(测试桩)
            }
            return new RetrievalResult(
                    rs.getLong("doc_id"),
                    rs.getString("name"),
                    rs.getInt("chunk_index"),
                    chunkId == 0 ? null : chunkId,
                    rs.getDouble("score"),
                    null, null, null,
                    snippet(content),
                    content,
                    rs.getString("source"),
                    parentIndex,
                    parentContent
            );
        };
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

    /**
     * 把块内容裁为列表预览(围绕开头截取,加省略号)。
     *
     * <p>2026-09-22(阶段 A):模型证据改用完整 {@code content},这里只是
     * 列表展示的短摘录——500 字符截断不再影响模型能看到的内容。
     */
    static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_CHARS ? content : content.substring(0, SNIPPET_CHARS) + "…";
    }

    /**
     * 围绕查询词首次出现位置截取预览(阶段 A:让用户看到「为什么命中」)。
     *
     * <p>查询词不在正文中(纯语义命中)时回落到开头截取。截取窗口前留
     * 80 字符上下文;窗口内首尾按需加省略号。
     *
     * <p>定位策略:整串找不到时按空白/标点拆词,取**最长命中词**的位置——
     * 自然语言查询(「数据库备份口令 NORA-BACKUP」)很少整串原样出现在正文,
     * 拆词后能落到真正的命中处。
     */
    static String snippetAround(String content, String query) {
        if (content == null) {
            return "";
        }
        if (content.length() <= SNIPPET_CHARS) {
            return content;
        }
        int hit = -1;
        if (query != null && !query.isBlank()) {
            String probe = query.strip();
            if (probe.length() > 30) {
                probe = probe.substring(0, 30);
            }
            hit = content.toLowerCase().indexOf(probe.toLowerCase());
            if (hit < 0) {
                // 拆词定位:取正文中命中的最长词(≥2 字符)
                String lower = content.toLowerCase();
                for (String token : probe.split("[\\s,，。;；、/]+")) {
                    if (token.length() < 2) {
                        continue;
                    }
                    int at = lower.indexOf(token.toLowerCase());
                    if (at >= 0) {
                        hit = at;
                        break;
                    }
                }
            }
        }
        if (hit < 0) {
            return content.substring(0, SNIPPET_CHARS) + "…";
        }
        int start = Math.max(0, hit - 80);
        int end = Math.min(content.length(), start + SNIPPET_CHARS);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append('…');
        }
        sb.append(content, start, end);
        if (end < content.length()) {
            sb.append('…');
        }
        return sb.toString();
    }
}
