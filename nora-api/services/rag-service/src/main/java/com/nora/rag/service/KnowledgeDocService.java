package com.nora.rag.service;

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
                "SELECT id, name, source, chunks, status, size, quality, updated_at FROM schema_rag.knowledge_doc ORDER BY updated_at DESC, id DESC",
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
                "SELECT id, name, source, chunks, status, size, quality, updated_at FROM schema_rag.knowledge_doc WHERE id = ?",
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

    /** Index stats snapshot shaped for the IndexStats frontend type. */
    public IndexStatsView getIndexStats() {
        Long totalDocs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc", Long.class);
        Long totalChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_chunk", Long.class);
        Long pendingDocs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc WHERE status = 'processing'", Long.class);
        Timestamp lastUpdate = jdbcTemplate.query(
                "SELECT max(updated_at) FROM schema_rag.knowledge_doc",
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
