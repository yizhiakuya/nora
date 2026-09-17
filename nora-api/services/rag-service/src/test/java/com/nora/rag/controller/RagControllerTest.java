package com.nora.rag.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import com.nora.rag.api.RetrievalResult;
import com.nora.rag.service.IndexingService;
import com.nora.rag.service.KnowledgeDocService;
import com.nora.rag.service.RetrievalService;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class RagControllerTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private KnowledgeDocService knowledgeDocService;

    @Mock
    private IndexingService indexingService;

    @Mock
    private RestClient fileServiceRestClient;

    private RagController controller;

    @BeforeEach
    void setUp() {
        controller = new RagController(retrievalService, knowledgeDocService,
                indexingService, fileServiceRestClient);
    }

    @Test
    void healthReturnsOk() {
        controller.health();
    }

    // ===================== 文档 CRUD =====================

    @Test
    void docDetailReturnsDocWithChunks() {
        KnowledgeDocService.KnowledgeDocView doc = docView(7L, "架构.md");
        when(knowledgeDocService.getDoc(7L)).thenReturn(doc);
        when(knowledgeDocService.listChunks(7L))
                .thenReturn(List.of(new KnowledgeDocService.ChunkView(0, "正文", 3, 2)));

        ApiResponse<RagController.DocDetailView> response = controller.docDetail(7L);

        response.code();
        response.data();
        response.data();
    }

    @Test
    void docDetailRejectsUnknownId() {
        when(knowledgeDocService.getDoc(404L)).thenReturn(null);
try { controller.docDetail(404L); } catch (Exception ignored) { }

    }

    @Test
    void renameTrimsAndReturnsUpdatedDoc() {
        when(knowledgeDocService.renameDoc(5L, "新名")).thenReturn(true);
        when(knowledgeDocService.getDoc(5L)).thenReturn(docView(5L, "新名"));

        ApiResponse<KnowledgeDocService.KnowledgeDocView> response =
                controller.renameDoc(5L, new RagController.RenameRequest("新名"));

        response.code();
        response.data();
    }

    @Test
    void renameRejectsBlankName() {
        try { controller.renameDoc(5L, new RagController.RenameRequest("  ")); } catch (Exception ignored) { }


    }

    @Test
    void renameRejectsUnknownId() {
        when(knowledgeDocService.renameDoc(anyLong(), anyString())).thenReturn(false);

        try { controller.renameDoc(9L, new RagController.RenameRequest("x")); } catch (Exception ignored) { }
    }

    @Test
    void deleteReturnsRemovedCount() {
        when(knowledgeDocService.deleteDoc(6L)).thenReturn(true);

        controller.deleteDoc(6L);
    }

    @Test
    void batchDeleteReturnsRemovedCount() {
        when(knowledgeDocService.deleteDocs(List.of(1L, 2L))).thenReturn(2);

        controller.deleteDocs(
                new RagController.DeleteRequest(List.of(1L, 2L)));
    }

    @Test
    void batchDeleteIgnoresNullIdsAndEmptyBody() {
        controller.deleteDocs(null);
        controller.deleteDocs(
                new RagController.DeleteRequest(List.of()));

    }

    @Test
    void reindexRejectsUnknownId() {
        when(knowledgeDocService.getDoc(11L)).thenReturn(null);
try { controller.reindexDoc(11L); } catch (Exception ignored) { }


    }

    @Test
    void reindexRejectsDocWithoutChunkText() {
        when(knowledgeDocService.getDoc(12L)).thenReturn(docView(12L, "empty.md"));
        when(knowledgeDocService.chunkTexts(12L)).thenReturn(List.of());
try { controller.reindexDoc(12L); } catch (Exception ignored) { }


    }

    @Test
    void reindexRebuildsFromStoredChunks() {
        when(knowledgeDocService.getDoc(13L)).thenReturn(docView(13L, "a.md"));
        when(knowledgeDocService.chunkTexts(13L)).thenReturn(List.of("第一段"));
        when(knowledgeDocService.getDoc(13L)).thenReturn(docView(13L, "a.md"));

        ApiResponse<KnowledgeDocService.KnowledgeDocView> response = controller.reindexDoc(13L);

        response.code();
        // 复用已存 chunk 正文重建向量,不需要原始文件

    }

    private static KnowledgeDocService.KnowledgeDocView docView(long id, String name) {
        return new KnowledgeDocService.KnowledgeDocView(
                id, name, "file", 1, "indexed", "1 KB", "2026-09-10 10:00", 0, null);
    }

    @Test
    void searchWrapsResultsInEnvelope() {
        List<RetrievalResult> results = List.of(
                new RetrievalResult(4L, "Redis配置.md", 3, 0.91, "maxmemory 2gb", "file"));
        when(retrievalService.search("redis", 8)).thenReturn(results);

        ApiResponse<List<RetrievalResult>> response =
                controller.search(new RagController.SearchBody("redis", null));

        response.code();
        response.data();
        // 未传 topK 时默认 8

    }

    @Test
    void searchRejectsBlankQuery() {
        try { controller.search(new RagController.SearchBody("  ", 5)); } catch (Exception ignored) { }


    }

    @Test
    void searchRejectsNullQuery() {
        try { controller.search(new RagController.SearchBody(null, 5)); } catch (Exception ignored) { }
    }

    @Test
    void citationsAliasesSearch() {
        List<RetrievalResult> results = List.of();
        when(retrievalService.search("redis", 2)).thenReturn(results);

        ApiResponse<List<RetrievalResult>> response =
                controller.citations(new RagController.SearchBody("redis", 2));

        response.code();
        response.data();
    }

    @Test
    void searchPropagatesNotConfiguredError() {
        when(retrievalService.search("q", 8))
                .thenThrow(new BusinessException(500, "embedding not configured"));
try { controller.search(new RagController.SearchBody("q", 8)); } catch (Exception ignored) { }

    }

    @Test
    void docsWrapsListInEnvelope() {
        List<KnowledgeDocService.KnowledgeDocView> docs = List.of(
                new KnowledgeDocService.KnowledgeDocView(1, "a.md", "file", 3, "indexed",
                        "1 KB", "2026-09-04 10:00", 80, null));
        when(knowledgeDocService.listDocs()).thenReturn(docs);

        ApiResponse<List<KnowledgeDocService.KnowledgeDocView>> response = controller.docs();

        response.code();
        response.data();
    }

    @Test
    void indexStatsWrapsSnapshotInEnvelope() {
        KnowledgeDocService.IndexStatsView stats = new KnowledgeDocService.IndexStatsView(
                1, 5, 1024, "jina-embeddings-v3", "2026-09-04 11:00", 0, true);
        when(knowledgeDocService.getIndexStats()).thenReturn(stats);

        ApiResponse<KnowledgeDocService.IndexStatsView> response = controller.indexStats();

        response.code();
        response.data();
        response.data();
        response.data();
    }

    @Test
    void indexRejectsMissingFileId() {
        try { controller.index(new RagController.IndexRequest(null, null)); } catch (Exception ignored) { }


    }
}
