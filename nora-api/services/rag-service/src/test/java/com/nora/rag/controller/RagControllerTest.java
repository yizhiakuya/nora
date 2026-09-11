package com.nora.rag.controller;

import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import com.nora.rag.api.RetrievalResult;
import com.nora.rag.service.IndexingService;
import com.nora.rag.service.KnowledgeDocService;
import com.nora.rag.service.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        assertEquals("ok", controller.health());
    }

    // ===================== 文档 CRUD =====================

    @Test
    void docDetailReturnsDocWithChunks() {
        KnowledgeDocService.KnowledgeDocView doc = docView(7L, "架构.md");
        when(knowledgeDocService.getDoc(7L)).thenReturn(doc);
        when(knowledgeDocService.listChunks(7L))
                .thenReturn(List.of(new KnowledgeDocService.ChunkView(0, "正文", 3, 2)));

        ApiResponse<RagController.DocDetailView> response = controller.docDetail(7L);

        assertEquals(0, response.code());
        assertEquals("架构.md", response.data().doc().name());
        assertEquals(1, response.data().chunks().size());
    }

    @Test
    void docDetailRejectsUnknownId() {
        when(knowledgeDocService.getDoc(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.docDetail(404L));

        assertEquals(404, ex.getCode());
    }

    @Test
    void renameTrimsAndReturnsUpdatedDoc() {
        when(knowledgeDocService.renameDoc(5L, "新名")).thenReturn(true);
        when(knowledgeDocService.getDoc(5L)).thenReturn(docView(5L, "新名"));

        ApiResponse<KnowledgeDocService.KnowledgeDocView> response =
                controller.renameDoc(5L, new RagController.RenameRequest("新名"));

        assertEquals(0, response.code());
        assertEquals("新名", response.data().name());
    }

    @Test
    void renameRejectsBlankName() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.renameDoc(5L, new RagController.RenameRequest("  ")));

        assertEquals(400, ex.getCode());
        verify(knowledgeDocService, never()).renameDoc(anyLong(), anyString());
    }

    @Test
    void renameRejectsUnknownId() {
        when(knowledgeDocService.renameDoc(anyLong(), anyString())).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> controller.renameDoc(9L, new RagController.RenameRequest("x")));
    }

    @Test
    void deleteReturnsRemovedCount() {
        when(knowledgeDocService.deleteDoc(6L)).thenReturn(true);

        assertEquals(1, controller.deleteDoc(6L).data().deleted());
    }

    @Test
    void batchDeleteReturnsRemovedCount() {
        when(knowledgeDocService.deleteDocs(List.of(1L, 2L))).thenReturn(2);

        assertEquals(2, controller.deleteDocs(
                new RagController.DeleteRequest(List.of(1L, 2L))).data().deleted());
    }

    @Test
    void batchDeleteIgnoresNullIdsAndEmptyBody() {
        assertEquals(0, controller.deleteDocs(null).data().deleted());
        assertEquals(0, controller.deleteDocs(
                new RagController.DeleteRequest(List.of())).data().deleted());
        verify(knowledgeDocService, never()).deleteDocs(anyList());
    }

    @Test
    void reindexRejectsUnknownId() {
        when(knowledgeDocService.getDoc(11L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.reindexDoc(11L));

        assertEquals(404, ex.getCode());
        verify(indexingService, never()).reindexChunks(anyLong(), anyList());
    }

    @Test
    void reindexRejectsDocWithoutChunkText() {
        when(knowledgeDocService.getDoc(12L)).thenReturn(docView(12L, "empty.md"));
        when(knowledgeDocService.chunkTexts(12L)).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.reindexDoc(12L));

        assertEquals(422, ex.getCode());
        verify(indexingService, never()).reindexChunks(anyLong(), anyList());
    }

    @Test
    void reindexRebuildsFromStoredChunks() {
        when(knowledgeDocService.getDoc(13L)).thenReturn(docView(13L, "a.md"));
        when(knowledgeDocService.chunkTexts(13L)).thenReturn(List.of("第一段"));
        when(knowledgeDocService.getDoc(13L)).thenReturn(docView(13L, "a.md"));

        ApiResponse<KnowledgeDocService.KnowledgeDocView> response = controller.reindexDoc(13L);

        assertEquals(0, response.code());
        // 复用已存 chunk 正文重建向量,不需要原始文件
        verify(indexingService).reindexChunks(13L, List.of("第一段"));
    }

    private static KnowledgeDocService.KnowledgeDocView docView(long id, String name) {
        return new KnowledgeDocService.KnowledgeDocView(
                id, name, "file", 1, "indexed", "1 KB", "2026-09-10 10:00", 0);
    }

    @Test
    void searchWrapsResultsInEnvelope() {
        List<RetrievalResult> results = List.of(
                new RetrievalResult(4L, "Redis配置.md", 3, 0.91, "maxmemory 2gb", "file"));
        when(retrievalService.search("redis", 8)).thenReturn(results);

        ApiResponse<List<RetrievalResult>> response =
                controller.search(new RagController.SearchBody("redis", null));

        assertEquals(0, response.code());
        assertEquals(results, response.data());
        // topK defaults to 8 when absent
        verify(retrievalService).search("redis", 8);
    }

    @Test
    void searchRejectsBlankQuery() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.search(new RagController.SearchBody("  ", 5)));

        assertEquals(400, ex.getCode());
        verify(retrievalService, never()).search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void searchRejectsNullQuery() {
        assertThrows(BusinessException.class,
                () -> controller.search(new RagController.SearchBody(null, 5)));
    }

    @Test
    void citationsAliasesSearch() {
        List<RetrievalResult> results = List.of();
        when(retrievalService.search("redis", 2)).thenReturn(results);

        ApiResponse<List<RetrievalResult>> response =
                controller.citations(new RagController.SearchBody("redis", 2));

        assertEquals(0, response.code());
        assertEquals(results, response.data());
    }

    @Test
    void searchPropagatesNotConfiguredError() {
        when(retrievalService.search("q", 8))
                .thenThrow(new BusinessException(500, "embedding not configured"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.search(new RagController.SearchBody("q", 8)));

        assertEquals("embedding not configured", ex.getMessage());
    }

    @Test
    void docsWrapsListInEnvelope() {
        List<KnowledgeDocService.KnowledgeDocView> docs = List.of(
                new KnowledgeDocService.KnowledgeDocView(1, "a.md", "file", 3, "indexed",
                        "1 KB", "2026-09-04 10:00", 80));
        when(knowledgeDocService.listDocs()).thenReturn(docs);

        ApiResponse<List<KnowledgeDocService.KnowledgeDocView>> response = controller.docs();

        assertEquals(0, response.code());
        assertEquals(docs, response.data());
    }

    @Test
    void indexStatsWrapsSnapshotInEnvelope() {
        KnowledgeDocService.IndexStatsView stats = new KnowledgeDocService.IndexStatsView(
                1, 5, 1024, "jina-embeddings-v3", "2026-09-04 11:00", 0, true, false);
        when(knowledgeDocService.getIndexStats()).thenReturn(stats);

        ApiResponse<KnowledgeDocService.IndexStatsView> response = controller.indexStats();

        assertEquals(0, response.code());
        assertEquals(stats, response.data());
        assertEquals(1024, response.data().vectorDim());
        assertEquals("jina-embeddings-v3", response.data().model());
    }

    @Test
    void indexRejectsMissingFileId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.index(new RagController.IndexRequest(null, null)));

        assertEquals(400, ex.getCode());
        verify(indexingService, never()).indexDocument(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
