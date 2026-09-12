package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    /**
     * Programmatic transactions for the failure path: {@code status='failed'}
     * must survive the rethrow of the very exception that caused it, and the
     * embedding HTTP call must not run inside a DB transaction (it holds a
     * pool connection for seconds). {@code @Transactional} can do neither —
     * rollback undoes the failed marker, and self-invocation skips the proxy.
     */
    private final TransactionTemplate txTemplate;

    public IndexingService(JdbcTemplate jdbcTemplate,
                           ChunkingService chunkingService,
                           EmbeddingService embeddingService,
                           EmbeddingProperties embeddingProperties,
                           TransactionTemplate txTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.embeddingProperties = embeddingProperties;
        this.txTemplate = txTemplate;
    }

    /**
     * Indexes a document: chunk + embed + persist. Re-indexing the same source
     * entity replaces its chunks (ON DELETE CASCADE), so an update never
     * leaves stale chunks behind.
     *
     * <p>Dedup key is {@code (source, source_id)} when a sourceId is given
     * (files, keyed by file-service fileId), otherwise {@code (source, name)} —
     * this is what makes "re-saving the same text replaces it" true for
     * name-keyed sources such as chat saves. Rows reached through the other key
     * are untouched, so a text doc never evicts a file doc sharing its name.
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
        // 软删旧文档(含其 chunks):partial unique index 释放 (source, source_id) /
        // (source, name) 去重键,新行才能插入;旧数据保留可审计
        if (sourceId != null) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id = ? AND deleted_at IS NULL)",
                    source, sourceId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id = ? AND deleted_at IS NULL",
                    source, sourceId);
        } else {
            // 同名覆盖:name-keyed 源(如对话保存)重复入库会堆行,按 (source,name) 先清旧
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id IS NULL AND name = ? AND deleted_at IS NULL)",
                    source, name);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id IS NULL AND name = ? AND deleted_at IS NULL",
                    source, name);
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
                    "UPDATE schema_rag.knowledge_doc SET chunks = 0, status = 'indexed', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
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
                    "UPDATE schema_rag.knowledge_doc SET chunks = ?, status = 'indexed', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                    chunks.size(), docId);
            log.info("Indexed doc '{}' with {} chunks ({}d, model {})",
                    name, chunks.size(), embeddingProperties.dimensions(), embeddingProperties.model());
            return docId;
        } catch (RuntimeException e) {
            markFailed(docId);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "embedding failed: " + e.getMessage());
        }
    }

    /**
     * Re-embeds a doc's existing chunks in place, keeping its id and chunk
     * boundaries.
     *
     * <p>Use cases: recovering {@code status='failed'} docs once the embedding
     * key is configured, and refreshing vectors after the embedding model or
     * dimension changed — stale vectors otherwise never match a query embedded
     * with the new model. The original file is not needed: chunk text lives in
     * {@code knowledge_chunk.content}.
     *
     * <p>Structure: no method-level {@code @Transactional}. The embedding HTTP
     * call runs outside any transaction (it must not pin a pool connection for
     * its whole duration); the delete+insert+status writes run in one short
     * programmatic transaction; and the {@code status='failed'} marker runs in
     * its own committed transaction before the exception propagates — under a
     * method-level rollback it would be silently undone and the doc would look
     * healthy while holding no/garbage vectors.
     *
     * @param docId doc to rebuild
     * @param texts chunk texts, in chunk_index order
     * @return number of chunks re-embedded
     * @throws BusinessException when embedding is not configured or fails
     */
    public int reindexChunks(long docId, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET chunks = 0, status = 'indexed', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                    docId);
            return 0;
        }
        // embedding 是外部 HTTP(Jina),事务外执行——不再长时间占用连接池
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedAll(texts);
        } catch (RuntimeException e) {
            markFailed(docId);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "re-index failed: " + e.getMessage());
        }
        try {
            txTemplate.executeWithoutResult(tx -> {
                jdbcTemplate.update(
                        "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id = ? AND deleted_at IS NULL", docId);
                jdbcTemplate.update(
                        "UPDATE schema_rag.knowledge_doc SET status = 'processing', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                        docId);
                for (int i = 0; i < texts.size(); i++) {
                    String content = texts.get(i);
                    jdbcTemplate.update(
                            "INSERT INTO schema_rag.knowledge_chunk (doc_id, chunk_index, content, embedding, token_count) VALUES (?, ?, ?, ?::vector, ?)",
                            docId, i, content,
                            RetrievalService.toPgVectorLiteral(embeddings.get(i)),
                            content == null ? 0 : content.length() / 4
                    );
                }
                jdbcTemplate.update(
                        "UPDATE schema_rag.knowledge_doc SET chunks = ?, status = 'indexed', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                        texts.size(), docId);
            });
            log.info("Re-indexed doc {} with {} chunks", docId, texts.size());
            return texts.size();
        } catch (RuntimeException e) {
            markFailed(docId);
            throw new BusinessException(502, "re-index failed: " + e.getMessage());
        }
    }

    /**
     * Marks the doc failed in its own committed transaction so the marker
     * survives the exception that follows (a {@code @Transactional} rollback
     * would undo it; see class javadoc).
     */
    private void markFailed(long docId) {
        try {
            txTemplate.executeWithoutResult(tx ->
                    jdbcTemplate.update(
                            "UPDATE schema_rag.knowledge_doc SET status = 'failed', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                            docId));
        } catch (RuntimeException markerError) {
            log.warn("failed to mark doc {} as failed: {}", docId, markerError.getMessage());
        }
    }
}
