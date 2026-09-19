package com.nora.rag.controller;

import java.util.List;
import java.util.Objects;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.nora.common.exception.BusinessException;
import com.nora.common.notification.NotificationPublisher;
import com.nora.common.response.ApiResponse;
import com.nora.rag.api.RetrievalResult;
import com.nora.rag.service.IndexingService;
import com.nora.rag.service.KnowledgeDocService;
import com.nora.rag.service.RetrievalService;

/**
 * RAG 端点,全部包在 {@link ApiResponse} 中,驼峰载荷与前端契约
 * (ragService.ts + types/index.ts)一致。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RetrievalService retrievalService;
    private final KnowledgeDocService knowledgeDocService;
    private final IndexingService indexingService;
    private final RestClient fileServiceRestClient;
    /** 通知事件发布(可空:测试构造不接;Kafka 不可达时静默降级,2026-09-19) */
    private final NotificationPublisher notificationPublisher;

    public RagController(RetrievalService retrievalService,
                         KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService,
                         RestClient fileServiceRestClient) {
        this(retrievalService, knowledgeDocService, indexingService, fileServiceRestClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RagController(RetrievalService retrievalService,
                         KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService,
                         RestClient fileServiceRestClient,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         NotificationPublisher notificationPublisher) {
        this.retrievalService = retrievalService;
        this.knowledgeDocService = knowledgeDocService;
        this.indexingService = indexingService;
        this.fileServiceRestClient = fileServiceRestClient;
        this.notificationPublisher = notificationPublisher;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * 触发上传文件的索引:从 file-service 拉提取文本 → 分块 + 嵌入 + 落库,
     * 然后回调 {@code POST /api/files/{fileId}/indexed}。Phase 1 同步执行。
     *
     * @param request {@code {fileId}} 加可选展示名
     * @return 落库的 KnowledgeDoc
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

        // 竞态补偿:索引耗时可观(embedding 数秒),期间文件可能已被删除
        // (用户点了「加入知识库」马上又删了它)。删除通知到达时文档行还不存在
        // (softDeleteByFileId 返回 0),索引完成后就留下一条存活文档——AI 仍能
        // 检索到已删文件的内容(正是该功能要防的)。这里复查文件存活:已删则
        // 把刚建的文档软删掉,让状态收敛到「文件已删 → 文档不可检索」。
        if (!isFileAlive(request.fileId())) {
            knowledgeDocService.softDeleteByFileId(request.fileId());
            log.info("file {} deleted during indexing; compensating soft-delete of doc {}", request.fileId(), docId);
            throw BusinessException.conflict("文件在索引期间已被删除,本次索引结果已撤销: " + request.fileId()
                    + "(若仍需索引请先从回收站恢复该文件)");
        }

        notifyFileIndexed(request.fileId());

        // 通知事件(Kafka,2026-09-19):文档索引入库完成——用户在任何页面都能
        // 从通知中心看到。发布失败静默,不影响索引主流程。
        if (notificationPublisher != null) {
            notificationPublisher.publish("indexed", "文档索引入库",
                    "「" + name + "」已完成清洗与向量化,AI 现在可以检索其内容。");
        }

        KnowledgeDocService.KnowledgeDocView doc = knowledgeDocService.getDoc(docId);
        return ApiResponse.ok(doc);
    }

    /** 文件是否仍存活(未被软删):按 ids 查 file-service,查不到视为已删。 */
    private boolean isFileAlive(Long fileId) {
        try {
            Envelope<JsonNode> envelope = fileServiceRestClient.get()
                    .uri("/api/files?ids={fileId}", fileId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return envelope != null && envelope.code() == 0 && envelope.data() != null
                    && envelope.data().isArray() && !envelope.data().isEmpty();
        } catch (Exception e) {
            // 查询失败时保守返回 true(不误删刚建的文档;极端情况宁可多留一份,
            // 用户可在知识库手动删除)
            return true;
        }
    }

    /** 全部知识文档(前端 KnowledgeDoc[])。 */
    @GetMapping("/docs")
    public ApiResponse<List<KnowledgeDocService.KnowledgeDocView>> docs() {
        return ApiResponse.ok(knowledgeDocService.listDocs());
    }

    /** 索引统计快照(前端 IndexStats,IndexStatus.tsx)。 */
    @GetMapping("/index/stats")
    public ApiResponse<KnowledgeDocService.IndexStatsView> indexStats() {
        return ApiResponse.ok(knowledgeDocService.getIndexStats());
    }

    /** 语义检索(前端 RetrievalResult[])。 */
    @PostMapping("/search")
    public ApiResponse<List<RetrievalResult>> search(@RequestBody SearchBody request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new BusinessException(400, "query is required");
        }
        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 8;
        return ApiResponse.ok(retrievalService.search(request.query(), topK));
    }

    /** /search 的引用别名(前端 Citation[])。 */
    @PostMapping("/citations")
    public ApiResponse<List<RetrievalResult>> citations(@RequestBody SearchBody request) {
        return search(request);
    }

    private FilePreviewBody fetchPreview(Long fileId) {
        try {
            // file-service 把载荷包在共享 ApiResponse 信封 {code,data,message} 里
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
            // 知识库是权威;回调失败只意味着 file-service 的 indexed 标记
            // 保持 false 直到下次索引运行。
            log.warn("file-service indexed callback failed for fileId={}: {}", fileId, e.getMessage());
        }
    }

    /**
     * POST /api/rag/index/text —— 按展示名索引原始文本
     * (按名索引的来源 "text";重存同名会替换其分块)。
     * Consumed by the chat "保存到知识库" action so saved answers survive
     * 刷新后仍在,且跨设备可检索。
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
     * 文档详情及其分块(前端详情抽屉)。
     *
     * @param id 文档 id
     * @return 文档 + 分块文本;id 未知时 404
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
     * 重命名文档;分块与向量不受影响。
     *
     * @param id      文档 id
     * @param request 请求体 {@code {name}}
     * @return 更新后的文档
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
     * 删除文档及其分块。
     *
     * @param id 文档 id
     * @return 删除的文档数(0 或 1)
     */
    @DeleteMapping("/docs/{id}")
    public ApiResponse<DeleteResult> deleteDoc(@PathVariable long id) {
        return ApiResponse.ok(new DeleteResult(knowledgeDocService.deleteDoc(id) ? 1 : 0));
    }

    /**
     * 批量删除。未知 id 被忽略并在 {@code deleted} 中体现。
     *
     * @param request 请求体 {@code {ids:[…]}}
     * @return 删除的文档数
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
     * 重建文档已存分块的向量。恢复失败文档,并在嵌入模型变更后刷新向量。
     *
     * @param id 文档 id
     * @return 更新后的文档
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

    /** 文档及其分块(前端详情抽屉)。 */
    public record DocDetailView(
            KnowledgeDocService.KnowledgeDocView doc,
            List<KnowledgeDocService.ChunkView> chunks
    ) {
    }

    /** PATCH /api/rag/docs/{id} 请求体。 */
    public record RenameRequest(String name) {
    }

    /** POST /api/rag/docs/delete 请求体。 */
    public record DeleteRequest(List<Long> ids) {
    }

    // ---------- 文件生命周期联动(2026-09-17) ----------

    /**
     * 文件删除/恢复/永久删除时,联动处理其知识库文档。
     *
     * <p>由 file-service 在文件生命周期事件上调用(内部端点):
     * {@code mode=soft}(软删,文件进回收站)/ {@code restore}(恢复)/
     * {@code purge}(永久删除)。幂等,按 source='file' + source_id 匹配。
     */
    @PostMapping("/docs/by-file/{fileId}")
    public ApiResponse<Integer> byFile(@PathVariable long fileId,
                                       @RequestParam("mode") String mode) {
        int affected = switch (mode) {
            case "soft" -> knowledgeDocService.softDeleteByFileId(fileId);
            case "restore" -> knowledgeDocService.restoreByFileId(fileId);
            case "purge" -> knowledgeDocService.purgeByFileId(fileId);
            default -> throw new BusinessException(400, "mode 必须是 soft/restore/purge,收到: " + mode);
        };
        return ApiResponse.ok(affected);
    }

    /** 删除结果;{@code deleted} 为实际删除的行数。 */
    public record DeleteResult(int deleted) {
    }

    /** POST /api/rag/index/text 请求体。 */
    public record TextIndexRequest(String name, String text) {
    }

    /** POST /api/rag/index 请求体。 */
    public record IndexRequest(Long fileId, String name) {
    }

    /** POST /api/rag/search 与 /api/rag/citations 请求体。 */
    public record SearchBody(String query, Integer topK) {
    }

    /** file-service 预览响应子集(ApiResponse 信封的 {@code data})。 */
    public record FilePreviewBody(Long fileId, String type, String textContent, String name, String size) {
    }

    /** file-service 返回的 ApiResponse 信封。 */
    public record Envelope<T>(int code, T data, String message) {
    }
}
