package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ingestion pipeline: pulls the extracted text from file-service, chunks it,
 * embeds the chunks, and persists {@code knowledge_doc} + {@code knowledge_chunk}.
 *
 * <p>Phase 1 runs the pipeline synchronously (file-service triggers it after
 * its own async extraction completes). Doc status transitions:
 * processing → indexed on success, processing → failed on embedding failure.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final EmbeddingProperties embeddingProperties;

    public IndexingService(JdbcTemplate jdbcTemplate,
                           ChunkingService chunkingService,
                           EmbeddingService embeddingService,
                           EmbeddingProperties embeddingProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * Indexes a document: chunk + embed + persist. Re-indexing the same source
     * entity (source + sourceId) replaces its chunks (ON DELETE CASCADE);
     * docs that merely share a display name are kept as separate rows.
     *
     * @param name     display name (usually the file name)
     * @param source   ingestion source, e.g. "file"
     * @param sourceId id of the source entity (file-service fileId); {@code null} for name-keyed sources
     * @param size     human-readable size label, e.g. "12 KB"
     * @param text     extracted plain text
     * @return persisted doc id
     * @throws BusinessException when embedding is not configured or fails
     */
    @Transactional
    public long indexDocument(String name, String source, Long sourceId, String size, String text) {
        if (sourceId != null) {
            jdbcTemplate.update("DELETE FROM schema_rag.knowledge_doc WHERE source = ? AND source_id = ?",
                    source, sourceId);
        }

        Long docId = jdbcTemplate.queryForObject(
                "INSERT INTO schema_rag.knowledge_doc (name, source, source_id, status, size) VALUES (?, ?, ?, 'processing', ?) RETURNING id",
                Long.class, name, source, sourceId, size);
        if (docId == null) {
            throw new BusinessException(500, "failed to create knowledge_doc");
        }

        List<String> chunks = chunkingService.chunk(text);
        if (chunks.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET chunks = 0, status = 'indexed', updated_at = now() WHERE id = ?",
                    docId);
            return docId;
        }

        try {
            List<float[]> embeddings = embeddingService.embedAll(chunks);
            for (int i = 0; i < chunks.size(); i++) {
                String content = chunks.get(i);
                jdbcTemplate.update(
                        "INSERT INTO schema_rag.knowledge_chunk (doc_id, chunk_index, content, embedding, token_count) VALUES (?, ?, ?, ?::vector, ?)",
                        docId, i, content,
                        RetrievalService.toPgVectorLiteral(embeddings.get(i)),
                        content.length() / 4 // rough chars-per-token approximation
                );
            }
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET chunks = ?, status = 'indexed', updated_at = now() WHERE id = ?",
                    chunks.size(), docId);
            log.info("Indexed doc '{}' with {} chunks ({}d, model {})",
                    name, chunks.size(), embeddingProperties.dimensions(), embeddingProperties.model());
            return docId;
        } catch (RuntimeException e) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET status = 'failed', updated_at = now() WHERE id = ?",
                    docId);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "embedding failed: " + e.getMessage());
        }
    }
}
