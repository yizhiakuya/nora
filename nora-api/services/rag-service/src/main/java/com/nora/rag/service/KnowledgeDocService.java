package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Read-side queries over {@code schema_rag.knowledge_doc}: the docs list and
 * the index stats snapshot consumed by the frontend KnowledgeDoc/IndexStats types.
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

    /** All knowledge docs, newest first, shaped for the KnowledgeDoc frontend type. */
    public List<KnowledgeDocView> listDocs() {
        return jdbcTemplate.query(
                "SELECT id, name, source, chunks, status, size, quality, updated_at FROM schema_rag.knowledge_doc WHERE deleted_at IS NULL ORDER BY updated_at DESC, id DESC",
                (rs, rowNum) -> new KnowledgeDocView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("source"),
                        rs.getInt("chunks"),
                        rs.getString("status"),
                        rs.getString("size"),
                        format(rs.getTimestamp("updated_at")),
                        rs.getInt("quality")
                )
        );
    }

    /** Single doc by id (used by the index endpoint response). */
    public KnowledgeDocView getDoc(long id) {
        List<KnowledgeDocView> docs = jdbcTemplate.query(
                "SELECT id, name, source, chunks, status, size, quality, updated_at FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new KnowledgeDocView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("source"),
                        rs.getInt("chunks"),
                        rs.getString("status"),
                        rs.getString("size"),
                        format(rs.getTimestamp("updated_at")),
                        rs.getInt("quality")
                ),
                id
        );
        return docs.isEmpty() ? null : docs.get(0);
    }

    /**
     * Soft-deletes a doc and its chunks (rows kept; queries filter them out).
     *
     * @param id doc id
     * @return false when the id does not exist (or is already deleted)
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
     * Soft-deletes many docs (and their chunks) in one statement.
     *
     * @param ids doc ids; null/empty is a no-op
     * @return number of soft-deleted rows
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

    /**
     * Renames a doc. Chunks and embeddings are untouched, so this is cheap and
     * safe even when no embedding API key is configured.
     *
     * <p>The doc's {@code (source, name)} uniqueness (V3 partial unique index,
     * name-keyed rows) is enforced here with a pre-check that yields a clean
     * 400 from the controller instead of a {@code DataIntegrityViolation} 500:
     * renaming onto an existing sibling name is a caller error, not a crash.
     *
     * @param id   doc id
     * @param name new display name (trimmed, non-blank)
     * @return false when the id does not exist
     * @throws com.nora.common.exception.BusinessException 409 when a same-source
     *         name-keyed doc already holds the new name
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

    /** A doc's chunks in index order, for the detail drawer. */
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
     * Chunk rows of a doc, as the raw material for re-indexing.
     *
     * @param id doc id
     * @return chunk contents in index order; empty when the doc has none
     */
    public List<String> chunkTexts(long id) {
        List<String> texts = jdbcTemplate.query(
                "SELECT content FROM schema_rag.knowledge_chunk WHERE doc_id = ? AND deleted_at IS NULL ORDER BY chunk_index",
                (rs, rowNum) -> rs.getString("content"),
                id);
        return texts == null ? List.of() : texts;
    }

    /** A single chunk as consumed by the frontend detail drawer. */
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
                (totalChunks != null && totalChunks > 0),
                false // graph index is not built in Phase 1
        );
    }

    private static String format(Timestamp timestamp) {
        return timestamp == null ? "—" : DISPLAY_FORMAT.format(timestamp.toLocalDateTime());
    }

    /** KnowledgeDoc row as consumed by the frontend (camelCase, display date). */
    public record KnowledgeDocView(
            long id,
            String name,
            String source,
            int chunks,
            String status,
            String size,
            String updatedAt,
            int quality
    ) {
    }

    /** IndexStats snapshot as consumed by the frontend IndexStatus.tsx. */
    public record IndexStatsView(
            long totalDocs,
            long totalChunks,
            int vectorDim,
            String model,
            String lastUpdate,
            long pendingDocs,
            boolean vectorReady,
            boolean graphReady
    ) {
    }
}
