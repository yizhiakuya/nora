package com.nora.rag.service;

import com.nora.rag.api.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private EmbeddingService embeddingService;

    private RetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalService(jdbcTemplate, embeddingService);
    }

    @Test
    void searchEmbedsQueryAndMapsRows() throws SQLException {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("redis 配置")).thenReturn(vector);

        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getString("name")).thenReturn("Redis配置.md");
        when(rs.getString("source")).thenReturn("file");
        when(rs.getString("content")).thenReturn("maxmemory 2gb");
        when(rs.getInt("chunk_index")).thenReturn(3);
        when(rs.getDouble("score")).thenReturn(0.87);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), eq(5)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<RetrievalResult> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<RetrievalResult> results = service.search("redis 配置", 5);

        assertEquals(1, results.size());
        RetrievalResult r = results.get(0);
        assertEquals("Redis配置.md", r.docName());
        assertEquals("file", r.source());
        assertEquals(3, r.chunkIndex());
        assertEquals(0.87, r.score());
        assertEquals("maxmemory 2gb", r.snippet());

        ArgumentCaptor<String> literalCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                literalCaptor.capture(), literalCaptor.capture(), eq(5));
        assertEquals("[0.1,0.2,0.3]", literalCaptor.getAllValues().get(0));
    }

    @Test
    void searchPropagatesNotConfiguredError() {
        when(embeddingService.embed(anyString()))
                .thenThrow(new com.nora.common.exception.BusinessException(500, "embedding not configured"));

        assertThrows(com.nora.common.exception.BusinessException.class,
                () -> service.search("anything", 8));
    }

    @Test
    void longContentIsTrimmedToSnippet() {
        String longContent = "x".repeat(600);
        String snippet = RetrievalService.snippet(longContent);

        assertEquals(501, snippet.length()); // 500 chars + ellipsis
        assertTrue(snippet.endsWith("…"));
    }

    @Test
    void nullContentYieldsEmptySnippet() {
        assertEquals("", RetrievalService.snippet(null));
    }

    @Test
    void pgVectorLiteralRendersFloats() {
        assertEquals("[0.5,1.0]", RetrievalService.toPgVectorLiteral(new float[]{0.5f, 1.0f}));
    }
}
