package com.nora.rag.service;

import com.nora.rag.config.EmbeddingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private KnowledgeDocService service;

    private final EmbeddingProperties properties =
            new EmbeddingProperties("test-key", "https://api.jina.ai/v1", "jina-embeddings-v3", 1024);

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocService(jdbcTemplate, properties);
    }

    @Test
    void indexStatsAggregatesCountsAndFormatsLastUpdate() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(3L)   // totalDocs
                .thenReturn(42L)  // totalChunks
                .thenReturn(1L);  // pendingDocs
        Timestamp lastUpdate = Timestamp.valueOf("2026-09-04 15:30:00");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(lastUpdate));

        KnowledgeDocService.IndexStatsView stats = service.getIndexStats();

        assertEquals(3, stats.totalDocs());
        assertEquals(42, stats.totalChunks());
        assertEquals(1, stats.pendingDocs());
        assertEquals(1024, stats.vectorDim());
        assertEquals("jina-embeddings-v3", stats.model());
        assertEquals("2026-09-04 15:30", stats.lastUpdate());
        assertTrue(stats.vectorReady());
        assertFalse(stats.graphReady());
    }

    @Test
    void indexStatsWithNoDataGivesDefaults() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(0L, 0L, 0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of());

        KnowledgeDocService.IndexStatsView stats = service.getIndexStats();

        assertEquals(0, stats.totalDocs());
        assertEquals(0, stats.totalChunks());
        assertEquals(0, stats.pendingDocs());
        assertEquals("—", stats.lastUpdate());
        assertFalse(stats.vectorReady());
        assertFalse(stats.graphReady());
    }

    @Test
    void listDocsMapsRowsToCamelCaseView() throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.getString("name")).thenReturn("架构设计.md");
        when(rs.getString("source")).thenReturn("file");
        when(rs.getInt("chunks")).thenReturn(6);
        when(rs.getString("status")).thenReturn("indexed");
        when(rs.getString("size")).thenReturn("12 KB");
        when(rs.getInt("quality")).thenReturn(85);
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf("2026-09-04 09:05:00"));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<KnowledgeDocService.KnowledgeDocView> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<KnowledgeDocService.KnowledgeDocView> docs = service.listDocs();

        assertEquals(1, docs.size());
        KnowledgeDocService.KnowledgeDocView doc = docs.get(0);
        assertEquals(7L, doc.id());
        assertEquals("架构设计.md", doc.name());
        assertEquals("file", doc.source());
        assertEquals(6, doc.chunks());
        assertEquals("indexed", doc.status());
        assertEquals("12 KB", doc.size());
        assertEquals("2026-09-04 09:05", doc.updatedAt());
        assertEquals(85, doc.quality());
    }

    @Test
    void embeddingPropertiesApplyDefaultsForBlanks() {
        EmbeddingProperties blank = new EmbeddingProperties("", " ", " ", null);

        assertEquals("", blank.apiKey());
        assertEquals(EmbeddingProperties.DEFAULT_BASE_URL, blank.baseUrl());
        assertEquals(EmbeddingProperties.DEFAULT_MODEL, blank.model());
        assertEquals(EmbeddingProperties.DEFAULT_DIMENSIONS, blank.dimensions());
        assertFalse(blank.configured());

        assertTrue(properties.configured());
    }
}
