package com.nora.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;

/**
 * 摄入管线:从 file-service 拉取提取文本 → 分块 → 嵌入 → 落
 * {@code knowledge_doc} + {@code knowledge_chunk}。
 *
 * <p>Phase 1 同步执行(file-service 在自己的异步提取完成后触发)。
 * 文档状态流转:processing → indexed(成功),processing → failed(嵌入失败)。
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final EmbeddingProperties embeddingProperties;
    /**
     * 失败路径用编程式事务:{@code status='failed'} 必须在引发它的异常重抛后
     * 依然留存,且嵌入 HTTP 调用不能在 DB 事务内跑(会占用连接池数秒)。
     * {@code @Transactional} 两件都做不到——回滚会撤销失败标记,自调用又
     * 绕过代理。
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
     * 索引一个文档:分块 + 嵌入 + 落库。重复索引同一来源实体会替换其块
     * (ON DELETE CASCADE),更新不会留下陈旧块。
     *
     * <p><b>事务阶段(2026-09-20 修复)</b>:不再整个方法一个 {@code @Transactional}
     * ——嵌入 HTTP 在事务外执行(不长时间占用池连接),失败标记用独立事务提交,
     * 不会被外层回滚撤销。三阶段:
     * <ol>
     *   <li>短事务:软删旧文档 + 插入 {@code processing} 新行(提交后失败才有行可标);</li>
     *   <li>事务外:分块 + 嵌入 HTTP;</li>
     *   <li>短事务:写块 + 标记 {@code indexed};失败则独立事务标 {@code failed}。</li>
     * </ol>
     * 此前单事务方案下,嵌入异常时 {@code markFailed} 与 processing 行一起回滚,
     * 文档看似从未索引(与 {@code reindexChunks} 的既有语义对齐)。
     *
     * <p>去重键:给了 sourceId 用 {@code (source, source_id)}(文件按
     * file-service fileId),否则用 {@code (source, name)}——后者让"重存同名
     * 文本即替换"对按名索引的来源(如对话保存)成立。经另一键可达的行不受
     * 影响,所以文本文档不会挤掉同名的文件文档。
     *
     * @param name     展示名(通常是文件名)
     * @param source   摄入来源,如 "file"
     * @param sourceId 来源实体 id(file-service fileId);按名索引的来源传 {@code null}
     * @param size     人类可读大小标签,如 "12 KB"
     * @param text     提取出的纯文本
     * @return 落库的文档 id
     * @throws BusinessException 嵌入未配置或失败时
     */
    public long indexDocument(String name, String source, Long sourceId, String size, String text) {
        // 阶段 1(短事务):软删旧文档(含其 chunks)+ 插入 processing 新行。
        // partial unique index 需要先释放 (source, source_id) / (source, name)
        // 去重键,新行才能插入;旧数据保留可审计。提交后失败标记有行可依。
        Long docId = txTemplate.execute(txStatus -> {
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
            return jdbcTemplate.queryForObject(
                    "INSERT INTO schema_rag.knowledge_doc (name, source, source_id, status, size) VALUES (?, ?, ?, 'processing', ?) RETURNING id",
                    Long.class, name, source, sourceId, size);
        });
        if (docId == null) {
            throw new BusinessException(500, "failed to create knowledge_doc");
        }

        List<String> chunks = chunkingService.chunk(text);
        if (chunks.isEmpty()) {
            txTemplate.executeWithoutResult(tx ->
                    jdbcTemplate.update(
                            "UPDATE schema_rag.knowledge_doc SET chunks = 0, status = 'indexed', updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                            docId));
            return docId;
        }

        // 阶段 2(事务外):嵌入 HTTP——不占用池连接数秒
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedAll(chunks);
        } catch (RuntimeException e) {
            markFailed(docId);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "embedding failed: " + e.getMessage());
        }

        // 阶段 3(短事务):写块 + 标记 indexed;失败独立事务标 failed(不回滚阶段 1)
        try {
            txTemplate.executeWithoutResult(tx -> {
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
            });
            log.info("Indexed doc '{}' with {} chunks ({}d, model {})",
                    name, chunks.size(), embeddingProperties.dimensions(), embeddingProperties.model());
            return docId;
        } catch (RuntimeException e) {
            markFailed(docId);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "indexing failed: " + e.getMessage());
        }
    }

    /**
     * 原地重建文档已有块的向量,保持 id 与分块边界。
     *
     * <p>用途:嵌入 key 配好后恢复 {@code status='failed'} 的文档;嵌入模型或
     * 维度变更后刷新向量——旧向量与新模型的查询永远对不上。不需要原始文件:
     * 块文本就在 {@code knowledge_chunk.content} 里。
     *
     * <p>结构:方法级不加 {@code @Transactional}。嵌入 HTTP 在任何事务外执行
     * (不能整个时长占着池连接);delete+insert+status 写在一个短编程式事务里;
     * {@code status='failed'} 标记在异常传播前以独立事务提交——若走方法级回滚
     * 它会被静默撤销,文档看似健康却没有任何/只有垃圾向量。
     *
     * @param docId 要重建的文档
     * @param texts 块文本,按 chunk_index 顺序
     * @return 重建的块数
     * @throws BusinessException 嵌入未配置或失败时
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
     * 以独立事务把文档标记为失败,让标记在随后的异常中留存
     * ({@code @Transactional} 回滚会撤销它;见类注释)。
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
