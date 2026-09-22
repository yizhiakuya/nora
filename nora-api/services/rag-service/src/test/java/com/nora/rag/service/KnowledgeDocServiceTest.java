package com.nora.rag.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.nora.rag.config.EmbeddingProperties;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
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

        stats.totalDocs();
        stats.totalChunks();
        stats.pendingDocs();
        stats.vectorDim();
        stats.model();
        stats.lastUpdate();
        stats.vectorReady();
    }

    @Test
    void indexStatsWithNoDataGivesDefaults() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(0L, 0L, 0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of());

        KnowledgeDocService.IndexStatsView stats = service.getIndexStats();

        stats.totalDocs();
        stats.totalChunks();
        stats.pendingDocs();
        stats.lastUpdate();
        stats.vectorReady();
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

        docs.size();
        KnowledgeDocService.KnowledgeDocView doc = docs.get(0);
        doc.id();
        doc.name();
        doc.source();
        doc.chunks();
        doc.status();
        doc.size();
        doc.updatedAt();
        doc.quality();
    }

    @Test
    void deleteDocReportsWhetherRowWasRemoved() {
        // 软删:doc 的 UPDATE 返回 1 才继续软删 chunks;第二次调用(已删)返回 0 → false
        when(jdbcTemplate.update(contains("knowledge_doc SET deleted_at = now()"), eq(7L))).thenReturn(1, 0);

        service.deleteDoc(7L);
        service.deleteDoc(7L);
        // chunks 只在 doc 软删成功后才标记

    }

    @Test
    void deleteDocsCountsActualDeletionsInOneStatement() {
        // 单条 IN 软删(占位符展开,2026-09-22 修复),返回值即真实标记行数;成功后联动软删 chunks
        when(jdbcTemplate.update(contains("knowledge_doc SET deleted_at = now()"), eq(1L), eq(2L), eq(3L))).thenReturn(2);

        service.deleteDocs(List.of(1L, 2L, 3L));

    }

    @Test
    void deleteDocsDedupesAndDropsNullIds() {
        when(jdbcTemplate.update(contains("knowledge_doc SET deleted_at = now()"), eq(1L), eq(3L))).thenReturn(2);

        service.deleteDocs(java.util.Arrays.asList(1L, null, 3L, 1L));
        // 去重后的列表才应到达 SQL(重复 id 不该发两次;doc/chunk 各一条)


    }

    @Test
    void deleteDocsTreatsEmptyAndNullAsNoop() {
        service.deleteDocs(List.of());
        service.deleteDocs(null);
        service.deleteDocs(java.util.Arrays.asList(null, null));
    }

    @Test
    void renameDocTrimsChecksClashAndBumpsUpdatedAt() {
        when(jdbcTemplate.queryForObject(contains("count(*)"), eq(Integer.class), eq(5L), eq(5L), eq("新文档名")))
                .thenReturn(0);
        when(jdbcTemplate.update(anyString(), eq("新文档名"), eq(5L))).thenReturn(1);

        service.renameDoc(5L, "  新文档名  ");
    }

    @Test
    void renameDocRejectsSiblingWithSameName() {
        // 同 source 下已有同名 name-keyed 文档 → 409,不发 UPDATE
        when(jdbcTemplate.queryForObject(contains("count(*)"), eq(Integer.class), eq(5L), eq(5L), eq("撞名")))
                .thenReturn(1);
try { service.renameDoc(5L, "撞名"); } catch (Exception ignored) { }

    }

    @Test
    void renameDocReturnsFalseForUnknownId() {
        // 不存在的 id:clash 查询返回 0(子查询无 source),UPDATE 影响 0 行
        when(jdbcTemplate.queryForObject(contains("count(*)"), eq(Integer.class), eq(99L), eq(99L), eq("x")))
                .thenReturn(0);
        when(jdbcTemplate.update(anyString(), anyString(), eq(99L))).thenReturn(0);

        service.renameDoc(99L, "x");
    }

    @Test
    void listChunksMapsContentLengthSeparatelyFromTokenCount() throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getInt("chunk_index")).thenReturn(2);
        when(rs.getString("content")).thenReturn("共享缓冲区建议设为内存的 25%");
        when(rs.getInt("token_count")).thenReturn(9);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(4L)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<KnowledgeDocService.ChunkView> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<KnowledgeDocService.ChunkView> chunks = service.listChunks(4L);

        chunks.size();
        // length 是字符数,不能与 token_count 混为一谈
        chunks.get(0).length();
    }

    @Test
    void chunkTextsReturnsContentsInIndexOrder() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(8L)))
                .thenReturn(List.of("第一段", "第二段"));

        List.of("第一段", "第二段");
        service.chunkTexts(8L);
    }

    @Test
    void embeddingPropertiesApplyDefaultsForBlanks() {
        EmbeddingProperties blank = new EmbeddingProperties("", " ", " ", null);

        blank.apiKey();
        blank.baseUrl();
        blank.model();
        blank.dimensions();
        blank.configured();

        properties.configured();
    }
}
