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
 * <p><b>先构建后发布(2026-09-22,知识库优化阶段 A):</b>此前先软删旧文档
 * 再插入新版并嵌入——嵌入失败时旧的可用资料也退出检索(方案 §3.2 P0)。
 * 现在:
 * <ol>
 *   <li>短事务:插入 {@code processing} 新行(**不动旧版**;V8 迁移后去重键
 *       只对 {@code indexed} 行生效,processing/failed 行不占用);</li>
 *   <li>短事务:分块正文先落库(无向量)——解析产物持久化,嵌入失败后
 *       {@code reindexChunks} 可直接用已存正文恢复,不必重新解析原文件;</li>
 *   <li>事务外:嵌入 HTTP(不占池连接);</li>
 *   <li>短事务:**原子发布**——写向量 + 软删同键旧存活行 + 新行置
 *       {@code indexed},同一事务内完成(查询只看到旧版或新版,无中间态)。</li>
 * </ol>
 *
 * <p>失败路径:新行标 {@code failed} 并写 {@code error} 原因;旧版保持
 * {@code indexed} 继续可检索——「新版更新失败,仍使用旧版」。首次导入失败
 * 则明确「暂不可检索」,重试可从已存解析产物恢复。
 *
 * <p>去重键:给了 sourceId 用 {@code (source, source_id)}(文件按 file-service
 * fileId),否则用 {@code (source, name)}——后者让"重存同名文本即替换"对
 * 按名索引的来源(如对话保存)成立。
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
     * 索引一个文档:分块 + 嵌入 + 原子发布。重复索引同一来源实体在**新版
     * 构建成功后**替换旧版;构建失败则旧版原样保留。
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
        return indexDocument(name, source, sourceId, size, text,
                ChunkingService.MODE_PLAIN, defaultBaseId());
    }

    /**
     * 带分段模式与资料库的索引(阶段 B,2026-09-22)。
     *
     * @param chunkMode plain / parent_child(方案 §5.2)
     * @param baseId    资料库 id;null = 默认库
     */
    public long indexDocument(String name, String source, Long sourceId, String size, String text,
                              String chunkMode, Long baseId) {
        String mode = chunkMode == null || chunkMode.isBlank() ? ChunkingService.MODE_PLAIN : chunkMode;
        Long targetBase = baseId != null ? baseId : defaultBaseId();
        // 阶段 1(短事务):插入 processing 新行,并清理同键的旧 failed 行
        // (它们已被本次构建取代;旧 indexed 行保留到发布切换,先构建后发布)。
        Long docId = txTemplate.execute(txStatus -> {
            softDeleteStaleFailedRows(source, sourceId, name, targetBase);
            return jdbcTemplate.queryForObject(
                    "INSERT INTO schema_rag.knowledge_doc (name, source, source_id, status, size, base_id, chunk_mode) VALUES (?, ?, ?, 'processing', ?, ?, ?) RETURNING id",
                    Long.class, name, source, sourceId, size, targetBase, mode);
        });
        if (docId == null) {
            throw new BusinessException(500, "failed to create knowledge_doc");
        }

        List<ChunkingService.ChunkPiece> pieces = chunkingService.chunk(text, mode);
        if (pieces.isEmpty()) {
            // 空文本:无块可嵌入,直接发布(0 块)。
            try {
                txTemplate.executeWithoutResult(tx -> publish(docId, source, sourceId, name, targetBase, 0));
            } catch (RuntimeException e) {
                markFailed(docId, "发布失败: " + rootMessage(e));
                throw wrap("indexing failed", e);
            }
            return docId;
        }

        // 阶段 2(短事务):分块正文先落库(embedding 为 NULL)——解析产物
        // 持久化:嵌入失败后 reindexChunks 可用已存正文恢复,不必重新上传。
        // 父子模式同时落父块信息(parent_index/parent_content)。
        try {
            txTemplate.executeWithoutResult(tx -> {
                for (int i = 0; i < pieces.size(); i++) {
                    ChunkingService.ChunkPiece piece = pieces.get(i);
                    String content = piece.content();
                    jdbcTemplate.update(
                            "INSERT INTO schema_rag.knowledge_chunk (doc_id, chunk_index, content, embedding, token_count, parent_index, parent_content) VALUES (?, ?, ?, NULL, ?, ?, ?)",
                            docId, i, content, content.length() / 4, // rough chars-per-token approximation
                            piece.parentIndex(), piece.parentContent()
                    );
                }
            });
        } catch (RuntimeException e) {
            markFailed(docId, "解析产物落库失败: " + rootMessage(e));
            throw wrap("chunk persistence failed", e);
        }

        // 阶段 3(事务外):嵌入 HTTP——不占用池连接数秒
        List<String> chunks = pieces.stream().map(ChunkingService.ChunkPiece::content).toList();
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedAll(chunks);
        } catch (RuntimeException e) {
            markFailed(docId, "嵌入失败: " + rootMessage(e));
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "embedding failed: " + e.getMessage());
        }

        // 阶段 4(短事务):写向量 + 原子发布(软删旧版 + 置 indexed 同一事务)
        try {
            txTemplate.executeWithoutResult(tx -> {
                for (int i = 0; i < chunks.size(); i++) {
                    jdbcTemplate.update(
                            "UPDATE schema_rag.knowledge_chunk SET embedding = ?::vector WHERE doc_id = ? AND chunk_index = ?",
                            RetrievalService.toPgVectorLiteral(embeddings.get(i)), docId, i);
                }
                publish(docId, source, sourceId, name, targetBase, chunks.size());
            });
            log.info("Indexed doc '{}' with {} chunks ({}d, model {})",
                    name, chunks.size(), embeddingProperties.dimensions(), embeddingProperties.model());
            return docId;
        } catch (RuntimeException e) {
            markFailed(docId, "发布失败: " + rootMessage(e));
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(502, "indexing failed: " + e.getMessage());
        }
    }

    /**
     * 原子发布(必须在事务内调用):软删同键的旧存活行 + 新行置 indexed。
     *
     * <p>同一事务里完成,唯一索引({@code idx_doc_source} /
     * {@code idx_doc_source_name},只对 indexed 行生效)不会看到两行并存的
     * 中间态;检索侧只看到旧版或新版。
     *
     * <p><b>发布守卫(2026-09-22 阶段 A):</b>构建期间本行可能已被生命周期
     * 事件软删(文件删除/永久删除,或更新版本的发布取代)。此时**跳过整个
     * 发布**——否则会软删掉刚被「恢复」恢复回来的旧版本,留下没有任何
     * 可用版本的悬空状态(方案 §8 场景「构建中删除源文件不能发布复活资料」)。
     */
    private void publish(long docId, String source, Long sourceId, String name, Long baseId, int chunkCount) {
        Integer alive = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL",
                Integer.class, docId);
        if (alive == null || alive == 0) {
            // 构建期间本行已被生命周期事件软删:不发布(不复活),但把状态收尾为
            // failed + 原因——否则行停在 processing,若用户从回收站恢复文件,
            // 界面上会留一个永久「处理中」的假象(2026-09-22 实测)。
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET status = 'failed', error = ?, updated_at = now() WHERE id = ?",
                    "构建期间文件被删除,本次索引已取消;恢复文件后可重新索引", docId);
            log.info("doc {} was superseded (soft-deleted) during build; publish skipped", docId);
            return;
        }
        // 1) 软删同键的其它存活行(旧 indexed 行被替换;旧 processing 行是
        //    并发重复提交,以本次为准;旧 failed 行已被新版本取代)。
        //    2026-09-22(阶段 B 修复):按名索引(sourceId 为 NULL)的去重键
        //    含 base_id——同名文档在**不同资料库**各自独立,不再跨库互相覆盖
        //    (实测:两个评测库放同名语料,后建的库把先建的库清空了)。
        if (sourceId != null) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id = ? AND deleted_at IS NULL AND id <> ?)",
                    source, sourceId, docId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id = ? AND deleted_at IS NULL AND id <> ?",
                    source, sourceId, docId);
        } else {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id IS NULL AND name = ? "
                            + "AND (base_id = ? OR (base_id IS NULL AND ? IS NULL)) AND deleted_at IS NULL AND id <> ?)",
                    source, name, baseId, baseId, docId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id IS NULL AND name = ? "
                            + "AND (base_id = ? OR (base_id IS NULL AND ? IS NULL)) AND deleted_at IS NULL AND id <> ?",
                    source, name, baseId, baseId, docId);
        }
        // 2) 本行置 indexed(发布点)
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET chunks = ?, status = 'indexed', error = NULL, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                chunkCount, docId);
    }

    /** 软删同键的旧 failed 行(新构建取代它们;旧 indexed 行不动;含 base 维度)。 */
    private void softDeleteStaleFailedRows(String source, Long sourceId, String name, Long baseId) {
        if (sourceId != null) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id = ? AND status = 'failed' AND deleted_at IS NULL)",
                    source, sourceId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id = ? AND status = 'failed' AND deleted_at IS NULL",
                    source, sourceId);
        } else {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id IS NULL AND name = ? "
                            + "AND (base_id = ? OR (base_id IS NULL AND ? IS NULL)) AND status = 'failed' AND deleted_at IS NULL)",
                    source, name, baseId, baseId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id IS NULL AND name = ? "
                            + "AND (base_id = ? OR (base_id IS NULL AND ? IS NULL)) AND status = 'failed' AND deleted_at IS NULL",
                    source, name, baseId, baseId);
        }
    }

    /**
     * 原地重建文档已有块的向量,保持 id 与分块边界。
     *
     * <p>用途:嵌入 key 配好后恢复 {@code status='failed'} 的文档;嵌入模型或
     * 维度变更后刷新向量。不需要原始文件:块文本就在
     * {@code knowledge_chunk.content} 里(阶段 A 起,首次嵌入失败的行也
     * 已有正文——先落正文再嵌入)。
     *
     * <p><b>发布语义(2026-09-22 阶段 A 修复):</b>重试一个 failed 行时,
     * 同键可能还有**存活的旧 indexed 行**(先构建后发布:更新失败旧版保留)。
     * 置 indexed 前必须软删同键旧存活行,否则撞
     * {@code idx_doc_source_name} 唯一索引(实测:reindex 报 duplicate key)。
     * 发布走与 indexDocument 相同的软删+置位短事务。
     *
     * @param docId 要重建的文档
     * @param texts 块文本,按 chunk_index 顺序
     * @return 重建的块数
     * @throws BusinessException 嵌入未配置或失败时
     */
    public int reindexChunks(long docId, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            txTemplate.executeWithoutResult(tx -> jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET chunks = 0, status = 'indexed', error = NULL, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                    docId));
            return 0;
        }
        // embedding 是外部 HTTP(Jina),事务外执行——不再长时间占用连接池
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedAll(texts);
        } catch (RuntimeException e) {
            markFailed(docId, "嵌入失败: " + rootMessage(e));
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
                // 发布:软删同键的其它存活行(可能是更新失败时保留的旧 indexed 版)
                // + 本行置 indexed,同一事务(唯一索引只对 indexed 行生效,无中间冲突)
                publishAfterRebuild(docId, texts.size());
            });
            log.info("Re-indexed doc {} with {} chunks", docId, texts.size());
            return texts.size();
        } catch (RuntimeException e) {
            markFailed(docId, "重建发布失败: " + rootMessage(e));
            throw new BusinessException(502, "re-index failed: " + e.getMessage());
        }
    }

    /** 重建后的发布:按本行的 (source, source_id/name) 软删其它存活行,再置 indexed。 */
    private void publishAfterRebuild(long docId, int chunkCount) {
        List<Object[]> rows = jdbcTemplate.query(
                "SELECT source, source_id, name, base_id FROM schema_rag.knowledge_doc WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new Object[]{rs.getString("source"),
                        rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
                        rs.getString("name"),
                        rs.getObject("base_id") == null ? null : rs.getLong("base_id")},
                docId);
        if (rows.isEmpty()) {
            return;
        }
        Object[] row = rows.get(0);
        String source = (String) row[0];
        Long sourceId = (Long) row[1];
        String name = (String) row[2];
        Long baseId = (Long) row[3];
        if (sourceId != null) {
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id = ? AND deleted_at IS NULL AND id <> ?)",
                    source, sourceId, docId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id = ? AND deleted_at IS NULL AND id <> ?",
                    source, sourceId, docId);
        } else {
            // 同库同名才算同键(阶段 B:不同资料库的同名文档各自独立)
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_chunk SET deleted_at = now() WHERE doc_id IN "
                            + "(SELECT id FROM schema_rag.knowledge_doc WHERE source = ? AND source_id IS NULL AND name = ? "
                            + "AND (base_id = ? OR (base_id IS NULL AND ? IS NULL)) AND deleted_at IS NULL AND id <> ?)",
                    source, name, baseId, baseId, docId);
            jdbcTemplate.update(
                    "UPDATE schema_rag.knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id IS NULL AND name = ? "
                            + "AND (base_id = ? OR (base_id IS NULL AND ? IS NULL)) AND deleted_at IS NULL AND id <> ?",
                    source, name, baseId, baseId, docId);
        }
        jdbcTemplate.update(
                "UPDATE schema_rag.knowledge_doc SET chunks = ?, status = 'indexed', error = NULL, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                chunkCount, docId);
    }

    /** 默认资料库 id(缓存;迁移保证存在)。 */
    private volatile Long defaultBaseIdCache;

    /** 默认资料库 id;查询失败返回 null(该文档不进任何库,列表仍可见)。 */
    Long defaultBaseId() {
        Long cached = defaultBaseIdCache;
        if (cached != null) {
            return cached;
        }
        try {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM schema_rag.knowledge_base WHERE is_default AND deleted_at IS NULL LIMIT 1",
                    Long.class);
            defaultBaseIdCache = id;
            return id;
        } catch (Exception e) {
            log.warn("default knowledge base lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 以独立事务把文档标记为失败并记录可读原因,让标记在随后的异常中留存
     * ({@code @Transactional} 回滚会撤销它;见类注释)。
     */
    private void markFailed(long docId, String reason) {
        try {
            txTemplate.executeWithoutResult(tx ->
                    jdbcTemplate.update(
                            "UPDATE schema_rag.knowledge_doc SET status = 'failed', error = ?, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                            reason == null ? null : (reason.length() > 2000 ? reason.substring(0, 2000) : reason),
                            docId));
        } catch (RuntimeException markerError) {
            log.warn("failed to mark doc {} as failed: {}", docId, markerError.getMessage());
        }
    }

    /** 异常链最深处的可读消息(用户排障用)。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    private static BusinessException wrap(String prefix, RuntimeException e) {
        if (e instanceof BusinessException be) {
            return be;
        }
        return new BusinessException(502, prefix + ": " + e.getMessage());
    }
}
