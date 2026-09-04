package com.nora.rag.controller;

import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import com.nora.rag.api.RetrievalResult;
import com.nora.rag.service.IndexingService;
import com.nora.rag.service.KnowledgeDocService;
import com.nora.rag.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * RAG endpoints, all wrapped in {@link ApiResponse} with camelCase payloads
 * matching the frontend contract (ragService.ts + types/index.ts).
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RetrievalService retrievalService;
    private final KnowledgeDocService knowledgeDocService;
    private final IndexingService indexingService;
    private final RestClient fileServiceRestClient;

    public RagController(RetrievalService retrievalService,
                         KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService,
                         RestClient fileServiceRestClient) {
        this.retrievalService = retrievalService;
        this.knowledgeDocService = knowledgeDocService;
        this.indexingService = indexingService;
        this.fileServiceRestClient = fileServiceRestClient;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * Triggers indexing for an uploaded file: pulls the extracted text from
     * file-service, chunks + embeds + persists it, then calls back
     * {@code POST /api/files/{fileId}/indexed}. Synchronous in Phase 1.
     *
     * @param request {@code {fileId}} plus optional display name
     * @return the persisted KnowledgeDoc
     */
    @PostMapping("/index")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> index(@RequestBody IndexRequest request) {
        if (request.fileId() == null) {
            throw new BusinessException(400, "fileId is required");
        }

        FilePreviewBody preview = fetchPreview(request.fileId());
        String text = preview.textContent();
        if (text == null || text.isBlank()) {
            throw new BusinessException(422, "file has no extractable text: " + request.fileId());
        }

        String name = request.name() != null && !request.name().isBlank()
                ? request.name()
                : preview.name() != null ? preview.name() : "file-" + request.fileId();
        String size = preview.size() != null ? preview.size() : "—";

        long docId = indexingService.indexDocument(name, "file", request.fileId(), size, text);

        notifyFileIndexed(request.fileId());

        KnowledgeDocService.KnowledgeDocView doc = knowledgeDocService.getDoc(docId);
        return ApiResponse.ok(doc);
    }

    /** All knowledge docs (frontend KnowledgeDoc[]). */
    @GetMapping("/docs")
    public ApiResponse<List<KnowledgeDocService.KnowledgeDocView>> docs() {
        return ApiResponse.ok(knowledgeDocService.listDocs());
    }

    /** Index stats snapshot (frontend IndexStats, IndexStatus.tsx). */
    @GetMapping("/index/stats")
    public ApiResponse<KnowledgeDocService.IndexStatsView> indexStats() {
        return ApiResponse.ok(knowledgeDocService.getIndexStats());
    }

    /** Semantic search (frontend RetrievalResult[]). */
    @PostMapping("/search")
    public ApiResponse<List<RetrievalResult>> search(@RequestBody SearchBody request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new BusinessException(400, "query is required");
        }
        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 8;
        return ApiResponse.ok(retrievalService.search(request.query(), topK));
    }

    /** Citation alias of /search (frontend Citation[]). */
    @PostMapping("/citations")
    public ApiResponse<List<RetrievalResult>> citations(@RequestBody SearchBody request) {
        return search(request);
    }

    private FilePreviewBody fetchPreview(Long fileId) {
        try {
            // file-service wraps payloads in the shared ApiResponse envelope {code,data,message}
            Envelope<FilePreviewBody> envelope = fileServiceRestClient.get()
                    .uri("/api/files/{fileId}/preview", fileId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null) {
                throw new BusinessException(502, "empty preview response from file-service");
            }
            if (envelope.code() != 0 || envelope.data() == null) {
                throw new BusinessException(502, "file-service preview error: " + envelope.message());
            }
            return envelope.data();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("file-service preview call failed for fileId={}: {}", fileId, e.getMessage());
            throw new BusinessException(502, "failed to fetch file preview from file-service: " + e.getMessage());
        }
    }

    private void notifyFileIndexed(Long fileId) {
        try {
            fileServiceRestClient.post()
                    .uri("/api/files/{fileId}/indexed", fileId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // The knowledge base is authoritative; a failed callback only means
            // file-service's indexed flag stays false until the next index run.
            log.warn("file-service indexed callback failed for fileId={}: {}", fileId, e.getMessage());
        }
    }

    /** POST /api/rag/index body. */
    public record IndexRequest(Long fileId, String name) {
    }

    /** POST /api/rag/search and /api/rag/citations body. */
    public record SearchBody(String query, Integer topK) {
    }

    /** file-service preview response subset (the {@code data} of the ApiResponse envelope). */
    public record FilePreviewBody(Long fileId, String type, String textContent, String name, String size) {
    }

    /** ApiResponse envelope as returned by file-service. */
    public record Envelope<T>(int code, T data, String message) {
    }
}
