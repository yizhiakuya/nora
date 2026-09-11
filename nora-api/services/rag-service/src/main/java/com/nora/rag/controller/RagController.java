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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

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

    /**
     * POST /api/rag/index/text — indexes raw text under a display name
     * (name-keyed source "text"; re-saving the same name replaces its chunks).
     * Consumed by the chat "保存到知识库" action so saved answers survive
     * refresh and are retrievable cross-device.
     */
    @PostMapping("/index/text")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> indexText(@RequestBody TextIndexRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        String name = (request.name() == null || request.name().isBlank())
                ? "对话保存 " + java.time.LocalDate.now() : request.name();
        long docId = indexingService.indexDocument(name, "text", null,
                (request.text().length() / 1024) + " KB", request.text());
        return ApiResponse.ok(knowledgeDocService.getDoc(docId));
    }

    /**
     * Doc detail with its chunks (frontend detail drawer).
     *
     * @param id doc id
     * @return doc plus chunk texts, or 404 when the id is unknown
     */
    @GetMapping("/docs/{id}")
    public ApiResponse<DocDetailView> docDetail(@PathVariable long id) {
        KnowledgeDocService.KnowledgeDocView doc = knowledgeDocService.getDoc(id);
        if (doc == null) {
            throw new BusinessException(404, "知识库文档不存在: " + id);
        }
        return ApiResponse.ok(new DocDetailView(doc, knowledgeDocService.listChunks(id)));
    }

    /**
     * Renames a doc; chunks and vectors are untouched.
     *
     * @param id      doc id
     * @param request {@code {name}}
     * @return the updated doc
     */
    @PatchMapping("/docs/{id}")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> renameDoc(@PathVariable long id,
                                                                      @RequestBody RenameRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        if (!knowledgeDocService.renameDoc(id, request.name())) {
            throw new BusinessException(404, "知识库文档不存在: " + id);
        }
        return ApiResponse.ok(knowledgeDocService.getDoc(id));
    }

    /**
     * Deletes a doc and its chunks.
     *
     * @param id doc id
     * @return count of removed docs (0 or 1)
     */
    @DeleteMapping("/docs/{id}")
    public ApiResponse<DeleteResult> deleteDoc(@PathVariable long id) {
        return ApiResponse.ok(new DeleteResult(knowledgeDocService.deleteDoc(id) ? 1 : 0));
    }

    /**
     * Batch delete. Unknown ids are ignored and reported in {@code deleted}.
     *
     * @param request {@code {ids:[…]}}
     * @return count of removed docs
     */
    @PostMapping("/docs/delete")
    public ApiResponse<DeleteResult> deleteDocs(@RequestBody DeleteRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            return ApiResponse.ok(new DeleteResult(0));
        }
        return ApiResponse.ok(new DeleteResult(
                knowledgeDocService.deleteDocs(request.ids().stream().filter(Objects::nonNull).toList())));
    }

    /**
     * Re-embeds a doc's stored chunks. Recovers failed docs and refreshes
     * vectors after an embedding model change.
     *
     * @param id doc id
     * @return the updated doc
     */
    @PostMapping("/docs/{id}/reindex")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> reindexDoc(@PathVariable long id) {
        if (knowledgeDocService.getDoc(id) == null) {
            throw new BusinessException(404, "知识库文档不存在: " + id);
        }
        List<String> texts = knowledgeDocService.chunkTexts(id);
        if (texts.isEmpty()) {
            throw new BusinessException(422,
                    "该文档没有可重建的 chunk 正文(源文件已入库时请重新上传): " + id);
        }
        indexingService.reindexChunks(id, texts);
        return ApiResponse.ok(knowledgeDocService.getDoc(id));
    }

    /** Doc plus its chunks (frontend detail drawer). */
    public record DocDetailView(
            KnowledgeDocService.KnowledgeDocView doc,
            List<KnowledgeDocService.ChunkView> chunks
    ) {
    }

    /** PATCH /api/rag/docs/{id} body. */
    public record RenameRequest(String name) {
    }

    /** POST /api/rag/docs/delete body. */
    public record DeleteRequest(List<Long> ids) {
    }

    /** Delete result; {@code deleted} counts rows actually removed. */
    public record DeleteResult(int deleted) {
    }

    /** POST /api/rag/index/text body. */
    public record TextIndexRequest(String name, String text) {
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
