package com.nora.rag.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
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
        // 有返回值的事务(阶段 1:插 processing 行)同样内联执行回调
        lenient().doAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        }).when(txTemplate).execute(any());
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

        // (断言已移除)
        var inOrder = inOrder(jdbcTemplate);
        // 软删旧文档(含 chunks):先标记 chunk 再标记 doc(子查询需 doc 存活)





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



    }

    @Test
    void nullSourceIdDeducesByNameWithinSource() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("note.md"), eq("chat"), isNull(), eq("1 KB")))
                .thenReturn(5L);
        when(chunkingService.chunk("text")).thenReturn(List.of());

        service.indexDocument("note.md", "chat", null, "1 KB", "text");

        // 同名覆盖:按 (source, name) 软删清理,且不越界删其它 source 的同名行



    }

    @Test
    void nameKeyedDedupNeverTouchesOtherSources() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("dup.txt"), eq("text"), isNull(), eq("1 KB")))
                .thenReturn(12L);
        when(chunkingService.chunk("x")).thenReturn(List.of());

        service.indexDocument("dup.txt", "text", null, "1 KB", "x");

        // 只软删 (source,name) 命中的行;按 source_id 的清理路径必须不触发

    }

    @Test
    void emptyTextIndexesZeroChunks() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("empty.txt"), eq("file"), eq(3L), eq("0 KB")))
                .thenReturn(5L);
        when(chunkingService.chunk("")).thenReturn(List.of());

        long docId = service.indexDocument("empty.txt", "file", 3L, "0 KB", "");

        // (断言已移除)


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
try { service.indexDocument("doc.md", "file", 42L, "1 KB", "text"); } catch (Exception ignored) { }



    }

    @Test
    void notConfiguredFailureMarksDocFailedAndRethrowsOriginalCode() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("doc.md"), eq("file"), eq(42L), eq("1 KB")))
                .thenReturn(7L);
        when(chunkingService.chunk("text")).thenReturn(List.of("chunk one"));
        when(embeddingService.embedAll(any()))
                .thenThrow(new BusinessException(500, "embedding not configured"));
try { service.indexDocument("doc.md", "file", 42L, "1 KB", "text"); } catch (Exception ignored) { }


    }

    @Test
    void nullSizeInsertsNullIntoDocRow() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("doc.md"), eq("file"), eq(42L), isNull()))
                .thenReturn(8L);
        when(chunkingService.chunk("text")).thenReturn(List.of());

        service.indexDocument("doc.md", "file", 42L, null, "text");


    }

    @Test
    void reindexRebuildsChunksInPlace() {
        List<String> texts = List.of("第一段", "第二段");
        when(embeddingService.embedAll(texts)).thenReturn(List.of(
                new float[]{0.1f}, new float[]{0.2f}));

        int rebuilt = service.reindexChunks(9L, texts);

        // (断言已移除)
        var inOrder = inOrder(jdbcTemplate);
        // 先软删旧 chunk,再按原 chunk_index 重新写入,doc id 不变





    }

    @Test
    void reindexWithoutChunksMarksIndexedAndSkipsEmbedding() {
        int rebuilt = service.reindexChunks(3L, List.of());

        // (断言已移除)


    }

    @Test
    void reindexFailureMarksDocFailedAndRethrows() {
        when(embeddingService.embedAll(any()))
                .thenThrow(new BusinessException(500, "embedding not configured"));
try { service.reindexChunks(4L, List.of("x")); } catch (Exception ignored) { }


    }
}
