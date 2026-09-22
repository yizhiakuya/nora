package com.nora.rag.service;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;

/**
 * {@code schema_rag.knowledge_doc} 的读侧查询:前端 KnowledgeDoc/IndexStats
 * 类型消费的文档列表与索引统计快照。
 */
@Service
public class KnowledgeDocService {

    static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProperties embeddingProperties;

    public KnowledgeDocService(JdbcTemplate jdbcTemplate, EmbeddingProperties embeddingProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProperties = embeddingProperties;
    }

    /** 全部知识文档,最新在前,按前端 KnowledgeDoc 类型塑形。 */
    public List<KnowledgeDocView> listDocs() {
        return jdbcTemplate.query(
                "SELECT id, name, source, chunks, status, size, quality, updated_at, source_id, error, warning, base_id, enabled, chunk_mode FROM schema_rag.knowledge_doc WHERE deleted_at IS NULL ORDER BY updated_at DESC, id DESC",
                (rs, rowNum) -> mapDoc(rs)
        );
    }

    /**
     * 记一条非致命解析告警(阶段 A:超限截断等;文档仍可检索)。
     *
     * @param id      文档 id
     * @param warning 告警文案
     */
    public void setWarning(long id, String warning) {
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET warning = ?, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                warning == null ? null : (warning.length() > 2000 ? warning.substring(0, 2000) : warning),
                id);
    }

    // ---------- 资料库(阶段 B,方案 §4) ----------

    /** 资料库视图(含文档数)。 */
    public record BaseView(long id, String name, String description, boolean isDefault, long docCount) {
    }

    /** 资料库列表(默认库在前)。 */
    public List<BaseView> listBases() {
        return jdbcTemplate.query(
                "SELECT b.id, b.name, b.description, b.is_default, "
                        + "(SELECT count(*) FROM schema_rag.knowledge_doc d WHERE d.base_id = b.id AND d.deleted_at IS NULL) AS doc_count "
                        + "FROM schema_rag.knowledge_base b WHERE b.deleted_at IS NULL ORDER BY b.is_default DESC, b.id",
                (rs, i) -> new BaseView(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("is_default"), rs.getLong("doc_count")));
    }

    /** 新建资料库(重名 409)。 */
    public BaseView createBase(String name, String description) {
        Integer clash = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_base WHERE name = ? AND deleted_at IS NULL",
                Integer.class, name);
        if (clash != null && clash > 0) {
            throw new BusinessException(409, "资料库已存在: " + name);
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO schema_rag.knowledge_base (name, description) VALUES (?, ?) RETURNING id",
                Long.class, name, description);
        return new BaseView(id == null ? -1 : id, name, description, false, 0);
    }

    /** 重命名/改描述;不存在返回 null。 */
    public BaseView renameBase(long id, String name, String description) {
        Integer clash = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_base WHERE name = ? AND id <> ? AND deleted_at IS NULL",
                Integer.class, name, id);
        if (clash != null && clash > 0) {
            throw new BusinessException(409, "资料库已存在: " + name);
        }
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_base SET name = ?, description = ? WHERE id = ? AND deleted_at IS NULL",
                name, description, id);
        if (updated == 0) {
            return null;
        }
        return listBases().stream().filter(b -> b.id() == id).findFirst().orElse(null);
    }

    /**
     * 删除资料库:默认库拒绝;库内文档移回默认库(不级联删除)。
     *
     * @return false = 不存在或默认库
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteBase(long id) {
        List<Boolean> isDefault = jdbcTemplate.queryForList(
                "SELECT is_default FROM schema_rag.knowledge_base WHERE id = ? AND deleted_at IS NULL",
                Boolean.class, id);
        if (isDefault.isEmpty() || Boolean.TRUE.equals(isDefault.get(0))) {
            return false;
        }
        Long fallback = jdbcTemplate.queryForObject(
                "SELECT id FROM schema_rag.knowledge_base WHERE is_default AND deleted_at IS NULL LIMIT 1",
                Long.class);
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET base_id = ? WHERE base_id = ? AND deleted_at IS NULL",
                fallback, id);
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_base SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id);
        return true;
    }

    // ---------- 文档停用(阶段 B,方案 §7) ----------

    /**
     * 停用/启用文档:停用=退出检索(保留数据与索引),与删除不同。
     *
     * @return 更新后的文档;不存在返回 null
     */
    public KnowledgeDocView setEnabled(long id, boolean enabled) {
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET enabled = ?, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                enabled, id);
        return updated == 0 ? null : getDoc(id);
    }

    // ---------- 检索记录(阶段 B,方案 §6.3/§7) ----------

    /** 检索记录视图(供「为什么找不到」回溯)。 */
    public record RetrievalLogView(long id, String query, Integer topK, String scopeJson,
                                   String status, int resultCount, Long durationMs,
                                   String detailJson, String createdAt) {
    }

    /** 最近 N 条检索记录(最新在前)。 */
    public List<RetrievalLogView> listRetrievalLogs(int limit) {
        return jdbcTemplate.query(
                "SELECT id, query, top_k, scope_json, status, result_count, duration_ms, detail_json, created_at "
                        + "FROM schema_rag.retrieval_log ORDER BY id DESC LIMIT ?",
                (rs, i) -> new RetrievalLogView(
                        rs.getLong("id"), rs.getString("query"), rs.getObject("top_k") == null ? null : rs.getInt("top_k"),
                        rs.getString("scope_json"), rs.getString("status"), rs.getInt("result_count"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getString("detail_json"), format(rs.getTimestamp("created_at"))),
                limit);
    }

    /** 按 id 取单文档(索引端点响应用)。 */
    public KnowledgeDocView getDoc(long id) {
        List<KnowledgeDocView> docs = jdbcTemplate.query(
                "SELECT id, name, source, chunks, status, size, quality, updated_at, source_id, error, warning, base_id, enabled, chunk_mode FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> mapDoc(rs),
                id
        );
        return docs.isEmpty() ? null : docs.get(0);
    }

    /** 行 → 视图(含阶段 A 诊断与阶段 B 归属/停用/分段模式)。 */
    private KnowledgeDocView mapDoc(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeDocView(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("source"),
                rs.getInt("chunks"),
                rs.getString("status"),
                rs.getString("size"),
                format(rs.getTimestamp("updated_at")),
                rs.getInt("quality"),
                rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
                rs.getString("error"),
                rs.getString("warning"),
                rs.getObject("base_id") == null ? null : rs.getLong("base_id"),
                rs.getBoolean("enabled"),
                rs.getString("chunk_mode")
        );
    }

    /**
     * 软删文档及其分块(行保留;查询过滤掉)。
     *
     * @param id 文档 id
     * @return id 不存在(或已删除)时 false
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteDoc(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id = ? AND deleted_at IS NULL", id);
        return true;
    }

    /**
     * 一条语句软删多个文档(及其分块)。
     *
     * @param ids 文档 id;null/空为无操作
     * @return 软删的行数
     */
    @org.springframework.transaction.annotation.Transactional
    public int deleteDocs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return 0;
        }
        // IN 占位符展开(2026-09-22 修复):此前写 "IN (?)" 传 List——
        // JdbcTemplate 把整个 List 当单个参数绑定,PG 驱动报
        // "bad SQL grammar"(单测 mock 掩盖了这一点,真实批量删除全失败)。
        String placeholders = String.join(",", distinct.stream().map(i -> "?").toList());
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE id IN (" + placeholders + ") AND deleted_at IS NULL",
                distinct.toArray());
        if (updated > 0) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN (" + placeholders + ") AND deleted_at IS NULL",
                    distinct.toArray());
        }
        return updated;
    }

    // ---------- 文件生命周期联动(2026-09-17) ----------
    //
    // 文件中心删除文件后,其索引进 RAG 的文档不应继续可检索(用户会困惑
    // 「我删了它 AI 怎么还知道」)。file-service 在删除/恢复/永久删除时
    // 调用这里,按 source='file' + source_id=fileId 联动处理。

    /**
     * 应用一次生命周期变更:版本确认 + 状态变化在**同一事务**内完成
     * (2026-09-22 阶段 A,方案 §5.4)。
     *
     * <p>此前版本检查(applyIfNewerVersion)与状态变化(softDeleteByFileId 等)
     * 是两个独立事务——检查通过后、状态变更前,更新的通知可能先应用,
     * 随后旧通知的状态变更仍然落地,把状态改回旧值。同一事务 + 版本行锁
     * 让同一文件的生命周期变更严格按版本序串行。
     *
     * @return -1 = 旧版本,忽略;>=0 = 实际影响行数
     */
    @org.springframework.transaction.annotation.Transactional
    public int applyLifecycle(long fileId, String mode, long version) {
        if (version > 0 && !applyIfNewerVersion(fileId, version)) {
            return -1;
        }
        return switch (mode) {
            case "soft" -> softDeleteByFileId(fileId);
            case "restore" -> restoreByFileId(fileId);
            case "purge" -> purgeByFileId(fileId);
            default -> throw new BusinessException(400, "mode 必须是 soft/restore/purge,收到: " + mode);
        };
    }

    /**
     * 乱序防护(R02,2026-09-20):仅当通知版本比已应用版本更新时推进记录。
     *
     * <p>HTTP 无顺序保证——「删除(v2)→恢复(v3)」时 v2 可能在 v3 之后到达;
     * 无此检查会把已恢复的文档再删掉。版本单调递增,只前进不回退。
     *
     * @param fileId  文件 id
     * @param version file-service 的单调版本
     * @return true = 可以执行本次变更;false = 旧版本,应忽略
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean applyIfNewerVersion(long fileId, long version) {
        // upsert 条件推进:插入(首见)或仅当新版本更大时更新;返回实际推进的行数
        int updated = jdbcTemplate.update(
                "INSERT INTO schema_rag.rag_lifecycle_version (file_id, applied_version, updated_at) "
                        + "VALUES (?, ?, now()) "
                        + "ON CONFLICT (file_id) DO UPDATE SET applied_version = EXCLUDED.applied_version, updated_at = now() "
                        + "WHERE schema_rag.rag_lifecycle_version.applied_version < EXCLUDED.applied_version",
                fileId, version);
        return updated > 0;
    }

    /**
     * 文件删除 → 联动软删其知识库文档(按 fileId)。
     *
     * @return 软删的文档数(0 = 该文件没索引过)
     */
    @org.springframework.transaction.annotation.Transactional
    public int softDeleteByFileId(long fileId) {
        List<Long> docIds = jdbcTemplate.queryForList(
                "SELECT id FROM schema_rag.knowledge_doc WHERE source = 'file' AND source_id = ? AND deleted_at IS NULL",
                Long.class, fileId);
        if (docIds.isEmpty()) {
            return 0;
        }
        String in = docIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE id IN (" + in + ") AND deleted_at IS NULL");
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN (" + in + ") AND deleted_at IS NULL");
        return updated;
    }

    /**
     * 文件恢复(回收站) → 联动恢复其知识库文档。
     *
     * <p>只恢复**最近一次可用(indexed)版本**的文档与**随本次文件删除一起
     * 软删**的 chunk:
     * <ul>
     *   <li>同 fileId 可能有多行 doc(更新发布时软删旧行、构建失败留 failed 行),
     *       全部恢复会撞 (source, source_id) 部分唯一索引 → 整条语句失败 →
     *       文件恢复了但知识库永远回不来;优先取 indexed 行(可用版本),
     *       再按 id 最大(最新);</li>
     *   <li>chunk 同理:更新发布会软删旧版本 chunk,其 deleted_at 更早;文件删除时
     *       软删的是当时存活的 chunk,deleted_at 与 doc 完全相同(同一事务 now())。
     *       按该时间戳精确恢复,旧版本 chunk 保持删除,避免同一内容检索出两份。</li>
     * </ul>
     *
     * @return 恢复的文档数
     */
    @org.springframework.transaction.annotation.Transactional
    public int restoreByFileId(long fileId) {
        List<Long> docIds = jdbcTemplate.queryForList(
                "SELECT id FROM schema_rag.knowledge_doc WHERE source = 'file' AND source_id = ? AND deleted_at IS NOT NULL "
                        + "ORDER BY (status = 'indexed') DESC, id DESC LIMIT 1",
                Long.class, fileId);
        if (docIds.isEmpty()) {
            return 0;
        }
        long docId = docIds.get(0);
        Timestamp docDeletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM schema_rag.knowledge_doc WHERE id = ?", Timestamp.class, docId);
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL", docId);
        if (docDeletedAt != null) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = NULL WHERE doc_id = ? AND deleted_at = ?",
                    docId, docDeletedAt);
        }
        return updated;
    }

    /**
     * 文件永久删除 → 联动永久删除其知识库文档与 chunk(不可恢复)。
     *
     * @return 永久删除的文档数
     */
    @org.springframework.transaction.annotation.Transactional
    public int purgeByFileId(long fileId) {
        List<Long> docIds = jdbcTemplate.queryForList(
                "SELECT id FROM schema_rag.knowledge_doc WHERE source = 'file' AND source_id = ?",
                Long.class, fileId);
        if (docIds.isEmpty()) {
            return 0;
        }
        String in = docIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM schema_rag.knowledge_chunk WHERE doc_id IN (" + in + ")");
        return jdbcTemplate.update("DELETE FROM schema_rag.knowledge_doc WHERE id IN (" + in + ")");
    }

    /**
     * 重命名文档。分块与嵌入不受影响,所以即使没配嵌入 API key 也便宜且安全。
     *
     * <p>文档的 {@code (source, name)} 唯一性(V3 部分唯一索引,按名索引的行)
     * 在这里用预检强制,让控制器给出干净的 400 而不是
     * {@code DataIntegrityViolation} 500:改成已存在的同源同名是调用方错误,
     * 不是崩溃。
     *
     * @param id   文档 id
     * @param name 新展示名(去空白,非空)
     * @return id 不存在时 false
     * @throws com.nora.common.exception.BusinessException 同源同名文档已占用
     *         新名称时 409
     */
    public boolean renameDoc(long id, String name) {
        String trimmed = name.trim();
        // 同库同名才算冲突(阶段 B:不同资料库的同名文档各自独立)
        Integer clash = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc d1 "
                        + "WHERE d1.id <> ? AND d1.source_id IS NULL AND d1.name = ? AND d1.deleted_at IS NULL "
                        + "AND d1.source = (SELECT source FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL) "
                        + "AND (d1.base_id = (SELECT base_id FROM schema_rag.knowledge_doc WHERE id = ?) "
                        + "     OR (d1.base_id IS NULL AND (SELECT base_id FROM schema_rag.knowledge_doc WHERE id = ?) IS NULL))",
                Integer.class, id, trimmed, id, id, id);
        if (clash != null && clash > 0) {
            throw new BusinessException(409, "同名文档已存在: " + trimmed);
        }
        return jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET name = ?, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                trimmed, id) > 0;
    }

    /** 文档的分块,按 index 序,供详情抽屉。 */
    public List<ChunkView> listChunks(long id) {
        return jdbcTemplate.query(
                "SELECT chunk_index, content, token_count FROM schema_rag.knowledge_chunk "
                        + "WHERE doc_id = ? AND deleted_at IS NULL ORDER BY chunk_index",
                (rs, rowNum) -> {
                    String content = rs.getString("content");
                    return new ChunkView(
                            rs.getInt("chunk_index"),
                            content,
                            rs.getInt("token_count"),
                            content == null ? 0 : content.length()
                    );
                },
                id
        );
    }

    /**
     * 文档的分块行,作为重建索引的原料。
     *
     * @param id 文档 id
     * @return 按 index 序的分块内容;文档没有分块时为空
     */
    public List<String> chunkTexts(long id) {
        List<String> texts = jdbcTemplate.query(
                "SELECT content FROM schema_rag.knowledge_chunk WHERE doc_id = ? AND deleted_at IS NULL ORDER BY chunk_index",
                (rs, rowNum) -> rs.getString("content"),
                id);
        return texts == null ? List.of() : texts;
    }

    /** 前端详情抽屉消费的单条分块。 */
    public record ChunkView(
            int chunkIndex,
            String content,
            int tokenCount,
            int length
    ) {
    }

    public IndexStatsView getIndexStats() {
        Long totalDocs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc WHERE deleted_at IS NULL", Long.class);
        Long totalChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_chunk WHERE deleted_at IS NULL", Long.class);
        Long pendingDocs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc WHERE status = 'processing' AND deleted_at IS NULL", Long.class);
        Timestamp lastUpdate = jdbcTemplate.query(
                "SELECT max(updated_at) FROM schema_rag.knowledge_doc WHERE deleted_at IS NULL",
                (rs, rowNum) -> (Timestamp) rs.getObject(1)).stream()
                .filter(ts -> ts != null)
                .max(Timestamp::compareTo)
                .orElse(null);

        return new IndexStatsView(
                totalDocs == null ? 0 : totalDocs,
                totalChunks == null ? 0 : totalChunks,
                embeddingProperties.dimensions(),
                embeddingProperties.model(),
                lastUpdate == null ? "—" : format(lastUpdate),
                pendingDocs == null ? 0 : pendingDocs,
                (totalChunks != null && totalChunks > 0)
        );
    }

    private static String format(Timestamp timestamp) {
        return timestamp == null ? "—" : DISPLAY_FORMAT.format(timestamp.toLocalDateTime());
    }

    /** 前端消费的 KnowledgeDoc 行(驼峰、展示日期)。 */
    public record KnowledgeDocView(
            long id,
            String name,
            String source,
            int chunks,
            String status,
            String size,
            String updatedAt,
            int quality,
            /** 来源文件 id(source='file' 时;前端可跳转文件中心)。 */
            Long sourceId,
            /** 构建失败原因(status='failed' 时;阶段 A:失败可见可重试)。 */
            String error,
            /** 非致命解析告警(如超限截断;文档仍可检索)。 */
            String warning,
            /** 资料库 id(阶段 B;null = 未归库)。 */
            Long baseId,
            /** 启用状态(阶段 B;false = 退出检索)。 */
            Boolean enabled,
            /** 分段模式(阶段 B:plain / parent_child)。 */
            String chunkMode
    ) {
        /** 兼容构造(旧调用点/测试):无诊断字段。 */
        public KnowledgeDocView(long id, String name, String source, int chunks, String status,
                                String size, String updatedAt, int quality, Long sourceId) {
            this(id, name, source, chunks, status, size, updatedAt, quality, sourceId, null, null, null, null, null);
        }

        /** 兼容构造(阶段 A 形态):无阶段 B 字段。 */
        public KnowledgeDocView(long id, String name, String source, int chunks, String status,
                                String size, String updatedAt, int quality, Long sourceId,
                                String error, String warning) {
            this(id, name, source, chunks, status, size, updatedAt, quality, sourceId, error, warning, null, null, null);
        }
    }

    /** 前端 IndexStatus.tsx 消费的 IndexStats 快照。 */
    public record IndexStatsView(
            long totalDocs,
            long totalChunks,
            int vectorDim,
            String model,
            String lastUpdate,
            long pendingDocs,
            boolean vectorReady
    ) {
    }
}
