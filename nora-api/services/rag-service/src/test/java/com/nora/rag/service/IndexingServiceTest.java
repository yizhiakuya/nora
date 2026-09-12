package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private TransactionTemplate txTemplate;

    private final EmbeddingProperties properties =
            new EmbeddingProperties("test-key", "https://api.jina.ai/v1", "jina-embeddings-v3", 1024);

    private IndexingService service;

    @BeforeEach
    void setUp() {
        service = new IndexingService(jdbcTemplate, chunkingService, embeddingService, properties, txTemplate);
        // programmatic tx 直接内联回调(单测不关心事务边界本身);executeWithoutResult 返回 void,用 doAnswer。
        // lenient:不经过事务路径的用例(如同名覆盖/空文本)不会触发它
        lenient().doAnswer(inv -> {
            java.util.function.Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    @Test
    void indexesDocumentWithChunksAndMarksIndexed() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("doc.md"), eq("file"), eq(42L), eq("1 KB")))
                .thenReturn(9L);
        List<String> chunks = List.of("chunk one", "chunk two");
        when(chunkingService.chunk("full text")).thenReturn(chunks);
        when(embeddingService.embedAll(chunks)).thenReturn(List.of(
                new float[]{0.1f}, new float[]{0.2f}));

        long docId = service.indexDocument("doc.md", "file", 42L, "1 KB", "full text");

        assertEquals(9L, docId);
        var inOrder = inOrder(jdbcTemplate);
        // 软删旧文档(含 chunks):先标记 chunk 再标记 doc(子查询需 doc 存活)
        inOrder.verify(jdbcTemplate).update(contains("SET deleted_at = now() WHERE doc_id IN"), eq("file"), eq(42L));
        inOrder.verify(jdbcTemplate).update(contains("knowledge_doc SET deleted_at = now()"), eq("file"), eq(42L));
        inOrder.verify(jdbcTemplate).update(contains("INSERT INTO schema_rag.knowledge_chunk"),
                eq(9L), eq(0), eq("chunk one"), eq("[0.1]"), anyInt());
        inOrder.verify(jdbcTemplate).update(contains("INSERT INTO schema_rag.knowledge_chunk"),
                eq(9L), eq(1), eq("chunk two"), eq("[0.2]"), anyInt());
        inOrder.verify(jdbcTemplate).update(contains("status = 'indexed'"), eq(2), eq(9L));
    }

    @Test
    void duplicateNameDifferentSourceIdDoesNotEvictOldDoc() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("dup.txt"), eq("file"), eq(7L), eq("1 KB")))
                .thenReturn(11L);
        when(chunkingService.chunk("text b")).thenReturn(List.of("chunk b"));
        when(embeddingService.embedAll(List.of("chunk b"))).thenReturn(List.of(new float[]{0.3f}));

        service.indexDocument("dup.txt", "file", 7L, "1 KB", "text b");

        // dedup 软删 scope 在 (source, source_id),绝不按 display name 误伤
        verify(jdbcTemplate).update(
                contains("knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id = ?"), eq("file"), eq(7L));
        verify(jdbcTemplate).update(
                contains("knowledge_chunk SET deleted_at = now() WHERE doc_id IN"), eq("file"), eq(7L));
        verify(jdbcTemplate, never()).update(contains("source_id IS NULL AND name = ?"), any(), anyString());
    }

    @Test
    void nullSourceIdDeducesByNameWithinSource() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("note.md"), eq("chat"), isNull(), eq("1 KB")))
                .thenReturn(5L);
        when(chunkingService.chunk("text")).thenReturn(List.of());

        service.indexDocument("note.md", "chat", null, "1 KB", "text");

        // 同名覆盖:按 (source, name) 软删清理,且不越界删其它 source 的同名行
        verify(jdbcTemplate).update(
                contains("knowledge_doc SET deleted_at = now() WHERE source = ? AND source_id IS NULL AND name = ?"),
                eq("chat"), eq("note.md"));
        verify(jdbcTemplate).update(
                contains("knowledge_chunk SET deleted_at = now() WHERE doc_id IN"), eq("chat"), eq("note.md"));
        verify(jdbcTemplate).update(contains("chunks = 0"), eq(5L));
    }

    @Test
    void nameKeyedDedupNeverTouchesOtherSources() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("dup.txt"), eq("text"), isNull(), eq("1 KB")))
                .thenReturn(12L);
        when(chunkingService.chunk("x")).thenReturn(List.of());

        service.indexDocument("dup.txt", "text", null, "1 KB", "x");

        // 只软删 (source,name) 命中的行;按 source_id 的清理路径必须不触发
        verify(jdbcTemplate, never()).update(contains("source = ? AND source_id = ? AND deleted_at IS NULL"), any(), any());
    }

    @Test
    void emptyTextIndexesZeroChunks() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("empty.txt"), eq("file"), eq(3L), eq("0 KB")))
                .thenReturn(5L);
        when(chunkingService.chunk("")).thenReturn(List.of());

        long docId = service.indexDocument("empty.txt", "file", 3L, "0 KB", "");

        assertEquals(5L, docId);
        verify(jdbcTemplate).update(contains("chunks = 0"), eq(5L));
        verify(embeddingService, never()).embedAll(any());
    }

    @Test
    void embeddingFailureMarksDocFailedAndRethrows() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("doc.md"), eq("file"), eq(42L), eq("1 KB")))
                .thenReturn(6L);
        List<String> chunks = List.of("chunk one");
        when(chunkingService.chunk("text")).thenReturn(chunks);
        when(embeddingService.embedAll(chunks))
                .thenThrow(new RuntimeException("jina timeout"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.indexDocument("doc.md", "file", 42L, "1 KB", "text"));

        assertEquals(502, ex.getCode());
        verify(jdbcTemplate).update(contains("status = 'failed'"), eq(6L));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO schema_rag.knowledge_chunk"),
                any(), anyInt(), anyString(), anyString(), anyInt());
    }

    @Test
    void notConfiguredFailureMarksDocFailedAndRethrowsOriginalCode() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("doc.md"), eq("file"), eq(42L), eq("1 KB")))
                .thenReturn(7L);
        when(chunkingService.chunk("text")).thenReturn(List.of("chunk one"));
        when(embeddingService.embedAll(any()))
                .thenThrow(new BusinessException(500, "embedding not configured"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.indexDocument("doc.md", "file", 42L, "1 KB", "text"));

        assertEquals(500, ex.getCode());
        assertEquals("embedding not configured", ex.getMessage());
        verify(jdbcTemplate).update(contains("status = 'failed'"), eq(7L));
    }

    @Test
    void nullSizeInsertsNullIntoDocRow() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("doc.md"), eq("file"), eq(42L), isNull()))
                .thenReturn(8L);
        when(chunkingService.chunk("text")).thenReturn(List.of());

        service.indexDocument("doc.md", "file", 42L, null, "text");

        verify(jdbcTemplate).update(contains("chunks = 0"), eq(8L));
    }

    @Test
    void reindexRebuildsChunksInPlace() {
        List<String> texts = List.of("第一段", "第二段");
        when(embeddingService.embedAll(texts)).thenReturn(List.of(
                new float[]{0.1f}, new float[]{0.2f}));

        int rebuilt = service.reindexChunks(9L, texts);

        assertEquals(2, rebuilt);
        var inOrder = inOrder(jdbcTemplate);
        // 先软删旧 chunk,再按原 chunk_index 重新写入,doc id 不变
        inOrder.verify(jdbcTemplate).update(contains("UPDATE schema_rag.knowledge_chunk SET deleted_at = now()"), eq(9L));
        inOrder.verify(jdbcTemplate).update(contains("status = 'processing'"), eq(9L));
        inOrder.verify(jdbcTemplate).update(contains("INSERT INTO schema_rag.knowledge_chunk"),
                eq(9L), eq(0), eq("第一段"), eq("[0.1]"), anyInt());
        inOrder.verify(jdbcTemplate).update(contains("INSERT INTO schema_rag.knowledge_chunk"),
                eq(9L), eq(1), eq("第二段"), eq("[0.2]"), anyInt());
        inOrder.verify(jdbcTemplate).update(contains("status = 'indexed'"), eq(2), eq(9L));
    }

    @Test
    void reindexWithoutChunksMarksIndexedAndSkipsEmbedding() {
        int rebuilt = service.reindexChunks(3L, List.of());

        assertEquals(0, rebuilt);
        verify(embeddingService, never()).embedAll(any());
        verify(jdbcTemplate).update(contains("chunks = 0"), eq(3L));
    }

    @Test
    void reindexFailureMarksDocFailedAndRethrows() {
        when(embeddingService.embedAll(any()))
                .thenThrow(new BusinessException(500, "embedding not configured"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reindexChunks(4L, List.of("x")));

        assertEquals(500, ex.getCode());
        assertEquals("embedding not configured", ex.getMessage());
        verify(jdbcTemplate).update(contains("status = 'failed'"), eq(4L));
    }
}
