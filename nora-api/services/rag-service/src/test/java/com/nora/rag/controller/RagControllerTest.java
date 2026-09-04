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

    @Test
    void searchWrapsResultsInEnvelope() {
        List<RetrievalResult> results = List.of(
                new RetrievalResult("Redis配置.md", 3, 0.91, "maxmemory 2gb", "file"));
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
