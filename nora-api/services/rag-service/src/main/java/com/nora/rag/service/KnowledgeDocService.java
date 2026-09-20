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
                "SELECT id, name, source, chunks, status, size, quality, updated_at, source_id FROM schema_rag.knowledge_doc WHERE deleted_at IS NULL ORDER BY updated_at DESC, id DESC",
                (rs, rowNum) -> new KnowledgeDocView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("source"),
                        rs.getInt("chunks"),
                        rs.getString("status"),
                        rs.getString("size"),
                        format(rs.getTimestamp("updated_at")),
                        rs.getInt("quality"),
                        rs.getObject("source_id") == null ? null : rs.getLong("source_id")
                )
        );
    }

    /** 按 id 取单文档(索引端点响应用)。 */
    public KnowledgeDocView getDoc(long id) {
        List<KnowledgeDocView> docs = jdbcTemplate.query(
                "SELECT id, name, source, chunks, status, size, quality, updated_at, source_id FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new KnowledgeDocView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("source"),
                        rs.getInt("chunks"),
                        rs.getString("status"),
                        rs.getString("size"),
                        format(rs.getTimestamp("updated_at")),
                        rs.getInt("quality"),
                        rs.getObject("source_id") == null ? null : rs.getLong("source_id")
                ),
                id
        );
        return docs.isEmpty() ? null : docs.get(0);
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
        int updated = jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE id IN (?) AND deleted_at IS NULL",
                distinct);
        if (updated > 0) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN (?) AND deleted_at IS NULL",
                    distinct);
        }
        return updated;
    }

    // ---------- 文件生命周期联动(2026-09-17) ----------
    //
    // 文件中心删除文件后,其索引进 RAG 的文档不应继续可检索(用户会困惑
    // 「我删了它 AI 怎么还知道」)。file-service 在删除/恢复/永久删除时
    // 调用这里,按 source='file' + source_id=fileId 联动处理。

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
     * <p>只恢复**最近一次索引**的文档与**随本次文件删除一起软删**的 chunk：
     * <ul>
     *   <li>同 fileId 可能有多行 doc(重复索引会软删旧行再插新行),全部恢复会撞
     *       (source, source_id) 部分唯一索引 → 整条语句失败 → 文件恢复了但知识库
     *       永远回不来;取 id 最大(最新)的一行;</li>
     *   <li>chunk 同理:重建索引会软删旧版本 chunk,其 deleted_at 更早;文件删除时
     *       软删的是当时存活的 chunk,deleted_at 与 doc 完全相同(同一事务 now())。
     *       按该时间戳精确恢复,旧版本 chunk 保持删除,避免同一内容检索出两份。</li>
     * </ul>
     *
     * @return 恢复的文档数
     */
    @org.springframework.transaction.annotation.Transactional
    public int restoreByFileId(long fileId) {
        List<Long> docIds = jdbcTemplate.queryForList(
                "SELECT id FROM schema_rag.knowledge_doc WHERE source = 'file' AND source_id = ? AND deleted_at IS NOT NULL ORDER BY id DESC LIMIT 1",
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
        Integer clash = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc "
                        + "WHERE id <> ? AND source = (SELECT source FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL) "
                        + "AND source_id IS NULL AND name = ? AND deleted_at IS NULL",
                Integer.class, id, id, trimmed);
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
            Long sourceId
    ) {
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
