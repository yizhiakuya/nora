package com.nora.rag.service;

import com.nora.common.exception.BusinessException;
import com.nora.rag.config.EmbeddingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private final EmbeddingProperties properties =
            new EmbeddingProperties("test-key", "https://api.jina.ai/v1", "jina-embeddings-v3", 1024);

    private IndexingService service;

    @BeforeEach
    void setUp() {
        service = new IndexingService(jdbcTemplate, chunkingService, embeddingService, properties);
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
        inOrder.verify(jdbcTemplate).update(contains("DELETE"), eq("file"), eq(42L));
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

        // dedup DELETE is scoped to (source, source_id), never by display name
        verify(jdbcTemplate).update(contains("source = ? AND source_id = ?"), eq("file"), eq(7L));
        verify(jdbcTemplate, never()).update(contains("WHERE name"), any(), anyString());
    }

    @Test
    void nullSourceIdSkipsDedupDelete() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                eq("note.md"), eq("chat"), isNull(), eq("1 KB")))
                .thenReturn(5L);
        when(chunkingService.chunk("text")).thenReturn(List.of());

        service.indexDocument("note.md", "chat", null, "1 KB", "text");

        verify(jdbcTemplate, never()).update(anyString(), anyString(), any());
        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyLong());
        verify(jdbcTemplate).update(contains("chunks = 0"), eq(5L));
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
}
