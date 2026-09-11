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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
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
        service = new RetrievalService(jdbcTemplate, embeddingService, null);
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

        // 向量侧:候选池取 max(topK, candidatePool=20)
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<RetrievalResult> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        // 关键词侧:无命中(本例不涉及)
        when(jdbcTemplate.query(contains("strict_word_similarity"), any(RowMapper.class),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<RetrievalResult> results = service.search("redis 配置", 5);

        assertEquals(1, results.size());
        RetrievalResult r = results.get(0);
        assertEquals("Redis配置.md", r.docName());
        assertEquals("file", r.source());
        assertEquals(3, r.chunkIndex());
        // 上报给前端的是原始余弦分(阈值判定要用它),融合分只用于排序
        assertEquals(0.87, r.score(), 1e-9);
        assertEquals("maxmemory 2gb", r.snippet());

        ArgumentCaptor<String> literalCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(contains("<=>"), any(RowMapper.class),
                literalCaptor.capture(), literalCaptor.capture(), anyInt());
        assertEquals("[0.1,0.2,0.3]", literalCaptor.getAllValues().get(0));
    }

    @Test
    void hitsBelowScoreFloorAreDropped() throws SQLException {
        float[] vector = {0.1f};
        when(embeddingService.embed("你好")).thenReturn(vector);
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(), any(), anyInt()))
                .thenReturn(List.of(new RetrievalResult(1L, "doc.md", 0, 0.12, "弱相关片段", "file")));

        List<RetrievalResult> results = service.search("你好", 6);

        // 余弦 0.12 低于 minScore(默认 0.30)→ 不污染 prompt
        assertTrue(results.isEmpty(), "低于阈值的片段不应注入");
    }

    @Test
    void hitsAboveScoreFloorAreKept() throws SQLException {
        when(embeddingService.embed("redis 配置")).thenReturn(new float[]{0.1f});
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(), any(), anyInt()))
                .thenReturn(List.of(new RetrievalResult(2L, "Redis配置.md", 0, 0.82, "maxmemory", "file")));
        when(jdbcTemplate.query(contains("strict_word_similarity"), any(RowMapper.class),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<RetrievalResult> results = service.search("redis 配置", 6);

        assertEquals(1, results.size());
        // 阈值不该把正常召回一起砍掉
        assertEquals(0.82, results.get(0).score(), 1e-9);
    }

    @Test
    void keywordOnlyHitAboveFloorIsKept() throws SQLException {
        when(embeddingService.embed("ERR_2077")).thenReturn(new float[]{0.1f});
        // 向量侧没召回,关键词侧命中(专有名词/错误码正是纯向量的短板)
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("strict_word_similarity"), any(RowMapper.class),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of(new RetrievalResult(3L, "errors.md", 2, 0.75, "ERR_2077 表示配额超限", "file")));

        List<RetrievalResult> results = service.search("ERR_2077", 6);

        assertEquals(1, results.size());
        assertEquals("errors.md", results.get(0).docName());
        assertEquals(0.75, results.get(0).score(), 1e-9);
    }

    @Test
    void shortQuerySkipsKeywordRanking() throws SQLException {
        when(embeddingService.embed("你好")).thenReturn(new float[]{0.1f});
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search("你好", 6);

        // 查询短于 minKeywordQueryLength(默认 4)→ 不发起关键词查询
        verify(jdbcTemplate, never()).query(contains("strict_word_similarity"),
                any(RowMapper.class), any(), any(), any(), anyInt());
    }

    @Test
    void keywordRankingFailureDegradesToVectorOnly() throws SQLException {
        when(embeddingService.embed("redis 配置")).thenReturn(new float[]{0.1f});
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(), any(), anyInt()))
                .thenReturn(List.of(new RetrievalResult(2L, "Redis配置.md", 0, 0.9, "maxmemory", "file")));
        when(jdbcTemplate.query(contains("strict_word_similarity"), any(RowMapper.class),
                any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("function strict_word_similarity does not exist"));

        List<RetrievalResult> results = service.search("redis 配置", 6);

        // pg_trgm 缺失不应让检索整体失败
        assertFalse(results.isEmpty());
        assertEquals("Redis配置.md", results.get(0).docName());
    }

    @Test
    void fusionRanksChunksPresentInBothListsFirst() {
        List<RetrievalResult> vector = List.of(
                new RetrievalResult(10L, "a.md", 0, 0.9, "sa", "file"),
                new RetrievalResult(20L, "b.md", 0, 0.8, "sb", "file"));
        List<RetrievalResult> keyword = List.of(
                new RetrievalResult(20L, "b.md", 0, 0.7, "sb", "file"));

        List<RetrievalResult> fused = service.fuse(vector, keyword);

        assertEquals(2, fused.size());
        // b 在两路都出现 → 融合分高于只在一路出现的 a
        assertEquals("b.md", fused.get(0).docName());
        assertEquals("a.md", fused.get(1).docName());
        // 上报的仍是原始相似度(两路都命中时取较高者),供前端低置信度判定
        assertEquals(0.8, fused.get(0).score(), 1e-9);
        assertEquals(0.9, fused.get(1).score(), 1e-9);
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
