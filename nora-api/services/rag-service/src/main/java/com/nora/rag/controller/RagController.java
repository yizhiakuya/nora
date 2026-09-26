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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.nora.common.exception.BusinessException;
import com.nora.common.notification.NotificationPublisher;
import com.nora.common.response.ApiResponse;
import com.nora.rag.api.RetrievalOutcome;
import com.nora.rag.service.ChunkingService;
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
    private final ChunkingService chunkingService;
    private final RestClient fileServiceRestClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    /** 通知事件发布(可空:测试构造不接;Kafka 不可达时静默降级,2026-09-19) */
    private final NotificationPublisher notificationPublisher;

    /** 测试构造(无通知发布器;Spring 用带 ChunkingService 的主构造器)。 */
    public RagController(RetrievalService retrievalService,
                         KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService,
                         RestClient fileServiceRestClient) {
        this(retrievalService, knowledgeDocService, indexingService, fileServiceRestClient, null);
    }

    /** 测试构造(带通知发布器)。 */
    public RagController(RetrievalService retrievalService,
                         KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService,
                         RestClient fileServiceRestClient,
                         NotificationPublisher notificationPublisher) {
        this(retrievalService, knowledgeDocService, indexingService, null,
                fileServiceRestClient, notificationPublisher);
    }

    /** 主装配构造(Spring 唯一 @Autowired;ChunkingService 缺省新建=测试兼容)。 */
    @org.springframework.beans.factory.annotation.Autowired
    public RagController(RetrievalService retrievalService,
                         KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         ChunkingService chunkingService,
                         RestClient fileServiceRestClient,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         NotificationPublisher notificationPublisher) {
        this.retrievalService = retrievalService;
        this.knowledgeDocService = knowledgeDocService;
        this.indexingService = indexingService;
        this.chunkingService = chunkingService != null ? chunkingService : new ChunkingService();
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
     * <p><b>分段配置与资料库接线(F5,2026-09-26)</b>:请求可带
     * {@code chunkMode/chunkSize/overlap/separator}(与 {@code /index/text} 同款,
     * 阶段 D 完整参数)与 {@code baseId}(目标资料库)。此前文件接口只有
     * fileId/name——前端发的参数被静默忽略,「文件按指定分段方式进入指定库」
     * 没有闭环。
     *
     * @param request {@code {fileId, name?, chunkMode?, chunkSize?, overlap?, separator?, baseId?}}
     * @return 落库的 KnowledgeDoc
     */
    @PostMapping("/index")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> index(@RequestBody IndexRequest request) {
        if (request.fileId() == null) {
            throw new BusinessException(400, "fileId is required");
        }
        // 目标资料库校验(F5):显式传了不存在的库要 404,不能静默落默认库
        if (request.baseId() != null) {
            Integer baseExists = knowledgeDocService.countBase(request.baseId());
            if (baseExists == null || baseExists == 0) {
                throw new BusinessException(404, "目标资料库不存在: " + request.baseId());
            }
        }

        FilePreviewBody preview = fetchPreview(request.fileId());
        String text = preview.textContent();
        String extractStatus = preview.extractStatus() == null ? "ok" : preview.extractStatus();
        // 解析诊断(2026-09-22 阶段 A):区分空白/损坏/超限,不再统一「没有文本」
        switch (extractStatus) {
            case "error" -> throw BusinessException.dependency("FILE_PARSE_FAILED",
                    "文件解析失败,无法入库: " + (preview.extractError() == null ? "未知原因" : preview.extractError()),
                    "请检查文件是否损坏或加密;扫描件/图片型 PDF 需要 OCR(暂不支持)");
            case "empty" -> throw new BusinessException(422,
                    "文件没有可提取的文本(空白文档、纯二进制,或扫描件/图片型 PDF——需要 OCR,暂不支持)");
            default -> { /* ok / truncated 继续 */ }
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException(422, "file has no extractable text: " + request.fileId());
        }

        String name = request.name() != null && !request.name().isBlank()
                ? request.name()
                : preview.name() != null ? preview.name() : "file-" + request.fileId();
        String size = preview.size() != null ? preview.size() : "—";

        // 分段配置(F5):与 /index/text 同款完整参数;缺省走默认(plain + 默认尺寸)
        ChunkingService.ChunkConfig config = new ChunkingService.ChunkConfig(
                request.chunkMode(), request.chunkSize(), request.overlap(), request.separator());
        long docId = indexingService.indexDocument(name, "file", request.fileId(), size, text,
                config, request.baseId());

        // 超限截断:文档仍可检索,但记告警让用户知道内容不完整(阶段 A)
        if ("truncated".equals(extractStatus)) {
            knowledgeDocService.setWarning(docId, preview.extractWarning() != null
                    ? preview.extractWarning()
                    : "内容超长,仅索引了前部分字符");
        }

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
            ApiResponse<JsonNode> envelope = fileServiceRestClient.get()
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

    /**
     * 语义检索(2026-09-22 阶段 A:返回带通道状态的结果集;阶段 B:范围过滤)。
     *
     * <p>此前检索异常被折叠为空列表,「检索坏了」与「资料里没有」不可区分。
     * 现在返回 {@link RetrievalOutcome}:status=ok/no_match/degraded/unavailable
     * + 每通道状态 + 完整证据(content)与预览(snippet)分离。
     *
     * <p>范围(baseId/docIds)同时进入两路候选查询;范围内无结果返回无结果,
     * 不自动扩大到全部资料。
     */
    @PostMapping("/search")
    public ApiResponse<RetrievalOutcome> search(@RequestBody SearchBody request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new BusinessException(400, "query is required");
        }
        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 8;
        RetrievalService.RetrievalScope scope = new RetrievalService.RetrievalScope(
                request.baseId(),
                request.docIds() == null || request.docIds().isEmpty() ? null : request.docIds(),
                request.sources() == null || request.sources().isEmpty() ? null : request.sources(),
                request.dateFrom(), request.dateTo());
        return ApiResponse.ok(retrievalService.searchWithStatus(request.query(), topK, scope));
    }

    /** /search 的引用别名(前端 Citation[];返回同一结果集)。 */
    @PostMapping("/citations")
    public ApiResponse<RetrievalOutcome> citations(@RequestBody SearchBody request) {
        return search(request);
    }

    // ---------- 资料库(阶段 B,方案 §4) ----------

    /** 资料库列表(含各库文档数)。 */
    @GetMapping("/bases")
    public ApiResponse<List<KnowledgeDocService.BaseView>> bases() {
        return ApiResponse.ok(knowledgeDocService.listBases());
    }

    /** 新建资料库。 */
    @PostMapping("/bases")
    public ApiResponse<KnowledgeDocService.BaseView> createBase(@RequestBody BaseRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        return ApiResponse.ok(knowledgeDocService.createBase(request.name().trim(), request.description()));
    }

    /** 重命名/改描述资料库。 */
    @PatchMapping("/bases/{id}")
    public ApiResponse<KnowledgeDocService.BaseView> renameBase(@PathVariable long id,
                                                                @RequestBody BaseRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        KnowledgeDocService.BaseView view = knowledgeDocService.renameBase(id, request.name().trim(), request.description());
        if (view == null) {
            throw new BusinessException(404, "资料库不存在: " + id);
        }
        return ApiResponse.ok(view);
    }

    /**
     * 删除资料库(默认库不可删;库内文档移回默认库,不级联删除——方案 §4:
     * 「从知识库移除不删除源文件」的同款语义)。
     */
    @DeleteMapping("/bases/{id}")
    public ApiResponse<DeleteResult> deleteBase(@PathVariable long id) {
        if (!knowledgeDocService.deleteBase(id)) {
            throw BusinessException.conflict("默认资料库不可删除,或资料库不存在: " + id);
        }
        return ApiResponse.ok(new DeleteResult(1));
    }

    /** 文档停用/启用(阶段 B:停用=退出检索,保留数据与索引)。 */
    @PostMapping("/docs/{id}/enabled")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> setDocEnabled(@PathVariable long id,
                                                                           @RequestBody EnabledRequest request) {
        if (request == null) {
            throw new BusinessException(400, "enabled is required");
        }
        KnowledgeDocService.KnowledgeDocView view = knowledgeDocService.setEnabled(id, request.enabled());
        if (view == null) {
            throw new BusinessException(404, "知识库文档不存在: " + id);
        }
        return ApiResponse.ok(view);
    }

    /**
     * 把文档移入资料库(阶段 B 闭环:建库后需要归库手段)。
     *
     * <p>目标库内已有同名同源文档(name-keyed)时返回 409——移库不能悄悄
     * 顶掉库内的另一篇文档(用户应先处理冲突)。
     */
    @PostMapping("/docs/{id}/base")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> setDocBase(@PathVariable long id,
                                                                        @RequestBody BaseMoveRequest request) {
        if (request == null) {
            throw new BusinessException(400, "baseId is required");
        }
        KnowledgeDocService.KnowledgeDocView view = knowledgeDocService.setBase(id, request.baseId());
        if (view == null) {
            throw new BusinessException(404, "知识库文档不存在: " + id);
        }
        return ApiResponse.ok(view);
    }

    /** 检索记录(阶段 B:最近 N 条,供「为什么找不到」回溯)。 */
    @GetMapping("/retrievals")
    public ApiResponse<List<KnowledgeDocService.RetrievalLogView>> retrievals(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(knowledgeDocService.listRetrievalLogs(Math.max(1, Math.min(limit, 100))));
    }

    private FilePreviewBody fetchPreview(Long fileId) {
        try {
            // file-service 把载荷包在共享 ApiResponse 信封 {code,data,message} 里
            ApiResponse<FilePreviewBody> envelope = fileServiceRestClient.get()
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
        // 分段配置(阶段 B 模式 / 阶段 D 完整参数):chunkSize/overlap/separator 可选
        ChunkingService.ChunkConfig config = new ChunkingService.ChunkConfig(
                request.chunkMode(), request.chunkSize(), request.overlap(), request.separator());
        long docId = indexingService.indexDocument(name, "text", null,
                (request.text().length() / 1024) + " KB", request.text(),
                config, request.baseId());
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

    // ---------- 分段级管理(阶段 D,Dify 同款) ----------

    /**
     * 编辑分段正文:只改内容,向量由本端点重算(正文变了旧向量失效)。
     *
     * @return 更新后的分段列表(前端刷新详情抽屉)
     */
    @PatchMapping("/chunks/{chunkId}")
    public ApiResponse<List<KnowledgeDocService.ChunkView>> updateChunk(@PathVariable long chunkId,
                                                                        @RequestBody ChunkContentRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new BusinessException(400, "content is required");
        }
        Long docId = knowledgeDocService.updateChunkContent(chunkId, request.content());
        if (docId == null) {
            throw new BusinessException(404, "分段不存在: " + chunkId);
        }
        // 重算该段向量(单段嵌入,快);失败不阻断编辑(下次 reindex 可恢复)
        try {
            indexingService.reindexChunk(docId, chunkId, request.content());
        } catch (Exception e) {
            log.warn("chunk {} vector recompute failed (edit kept, reindex later): {}", chunkId, e.getMessage());
        }
        return ApiResponse.ok(knowledgeDocService.listChunks(docId));
    }

    /** 停用/启用分段(退出检索/恢复;与文档停用同语义)。 */
    @PostMapping("/chunks/{chunkId}/enabled")
    public ApiResponse<List<KnowledgeDocService.ChunkView>> setChunkEnabled(@PathVariable long chunkId,
                                                                            @RequestBody EnabledRequest request) {
        if (request == null) {
            throw new BusinessException(400, "enabled is required");
        }
        Long docId = knowledgeDocService.setChunkEnabled(chunkId, request.enabled());
        if (docId == null) {
            throw new BusinessException(404, "分段不存在: " + chunkId);
        }
        return ApiResponse.ok(knowledgeDocService.listChunks(docId));
    }

    /** 删除分段(软删;剩余分段重新编号)。 */
    @DeleteMapping("/chunks/{chunkId}")
    public ApiResponse<List<KnowledgeDocService.ChunkView>> deleteChunk(@PathVariable long chunkId) {
        Long docId = knowledgeDocService.deleteChunk(chunkId);
        if (docId == null) {
            throw new BusinessException(404, "分段不存在: " + chunkId);
        }
        return ApiResponse.ok(knowledgeDocService.listChunks(docId));
    }

    /** 手动新增分段(插到文档末尾;向量立即重算,失败保留待 reindex)。 */
    @PostMapping("/docs/{id}/chunks")
    public ApiResponse<List<KnowledgeDocService.ChunkView>> addChunk(@PathVariable long id,
                                                                     @RequestBody ChunkContentRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new BusinessException(400, "content is required");
        }
        Long chunkId = knowledgeDocService.addChunk(id, request.content());
        if (chunkId == null) {
            throw new BusinessException(404, "知识库文档不存在: " + id);
        }
        try {
            indexingService.reindexChunk(id, chunkId, request.content());
        } catch (Exception e) {
            log.warn("new chunk {} vector compute failed (reindex later): {}", chunkId, e.getMessage());
        }
        return ApiResponse.ok(knowledgeDocService.listChunks(id));
    }

    /**
     * 分段预览(阶段 D,Dify 同款):不落库,用给定参数试切一段文本,
     * 返回分段结果与统计——导入前调参用。
     */
    @PostMapping("/chunk-preview")
    public ApiResponse<ChunkPreviewView> chunkPreview(@RequestBody ChunkPreviewRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new BusinessException(400, "text is required");
        }
        if (request.text().length() > 200_000) {
            throw new BusinessException(400, "预览文本过长(上限 200,000 字符)");
        }
        ChunkingService.ChunkConfig config = new ChunkingService.ChunkConfig(
                request.mode(), request.chunkSize(), request.overlap(), request.separator());
        List<ChunkingService.ChunkPiece> pieces = chunkingService.chunk(request.text(), config);
        List<ChunkPreviewItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(pieces.size(), 50); i++) {
            ChunkingService.ChunkPiece p = pieces.get(i);
            items.add(new ChunkPreviewItem(i, p.content().length(), p.content(),
                    p.parentIndex() != null));
        }
        return ApiResponse.ok(new ChunkPreviewView(
                config.normalized().mode(), config.normalized().chunkSize(), config.normalized().overlap(),
                pieces.size(), items));
    }

    /** POST /api/rag/chunk-preview 请求体。 */
    public record ChunkPreviewRequest(String text, String mode, Integer chunkSize,
                                      Integer overlap, String separator) {
    }

    /** 分段预览结果(前 50 段正文 + 总数;不落库)。 */
    public record ChunkPreviewView(String mode, int chunkSize, int overlap, int total,
                                   List<ChunkPreviewItem> chunks) {
    }

    /** 预览的单个分段。 */
    public record ChunkPreviewItem(int index, int length, String content, boolean hasParent) {
    }

    // ---------- 库级检索配置(阶段 D,Dify 同款) ----------

    /**
     * 保存资料库的检索配置(JSON:{mode,topK,minScore,vectorWeight,keywordWeight,rerankEnabled})。
     * 配置在检索时覆盖全局默认——不同库可用不同策略。
     */
    @PutMapping("/bases/{id}/retrieval-config")
    public ApiResponse<KnowledgeDocService.BaseView> setBaseRetrievalConfig(@PathVariable long id,
                                                                            @RequestBody RetrievalConfigRequest request) {
        if (request == null || request.config() == null || request.config().isBlank()) {
            throw new BusinessException(400, "config is required");
        }
        // 校验是合法 JSON(避免存进去在检索时才炸)
        try {
            objectMapper.readTree(request.config());
        } catch (Exception e) {
            throw new BusinessException(400, "config 必须是合法 JSON: " + e.getMessage());
        }
        KnowledgeDocService.BaseView view = knowledgeDocService.setRetrievalConfig(id, request.config());
        if (view == null) {
            throw new BusinessException(404, "资料库不存在: " + id);
        }
        return ApiResponse.ok(view);
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
     *
     * <p><b>乱序防护(R02,2026-09-20)</b>:{@code version} 为 file-service 的
     * 单调版本号(每次生命周期变化 +1)。本端记录每文件已应用的最大版本;
     * 版本 ≤ 已应用版本的在途旧请求直接忽略(返回 0),不复活旧状态——
     * 「删除→恢复→删除」的乱序投递最终与文件状态一致。缺省(旧调用方不带
     * version)按 0 处理,行为与旧版一致(无条件执行)。
     */
    @PostMapping("/docs/by-file/{fileId}")
    public ApiResponse<Integer> byFile(@PathVariable long fileId,
                                       @RequestParam("mode") String mode,
                                       @RequestParam(value = "version", defaultValue = "0") long version) {
        // 版本确认与状态变化在同一事务内(2026-09-22 阶段 A,方案 §5.4):
        // 此前两者分离,检查通过后更新的通知可能先应用、旧通知随后仍落地。
        int affected = knowledgeDocService.applyLifecycle(fileId, mode, version);
        if (affected < 0) {
            log.info("stale lifecycle notify ignored: file {} mode={} v{} (already applied newer)", fileId, mode, version);
            return ApiResponse.ok(0);
        }
        return ApiResponse.ok(affected);
    }

    /** 删除结果;{@code deleted} 为实际删除的行数。 */
    public record DeleteResult(int deleted) {
    }

    /** POST /api/rag/index/text 请求体(阶段 B:可带 chunkMode 与 baseId)。 */
    public record TextIndexRequest(String name, String text, String chunkMode, Long baseId,
                                   Integer chunkSize, Integer overlap, String separator) {
        /** 兼容构造(阶段 A 调用方)。 */
        public TextIndexRequest(String name, String text) {
            this(name, text, null, null, null, null, null);
        }

        /** 兼容构造(阶段 B 形态:模式 + 库)。 */
        public TextIndexRequest(String name, String text, String chunkMode, Long baseId) {
            this(name, text, chunkMode, baseId, null, null, null);
        }
    }

    /** POST /api/rag/index 请求体(F5:分段配置与资料库与 /index/text 对齐)。 */
    public record IndexRequest(Long fileId, String name, String chunkMode, Integer chunkSize,
                               Integer overlap, String separator, Long baseId) {
        /** 兼容构造(旧调用方:仅 fileId/name)。 */
        public IndexRequest(Long fileId, String name) {
            this(fileId, name, null, null, null, null, null);
        }
    }

    /** POST /api/rag/search 与 /api/rag/citations 请求体(阶段 B:范围参数)。 */
    public record SearchBody(String query, Integer topK, Long baseId, List<Long> docIds,
                             List<String> sources, String dateFrom, String dateTo) {
        /** 兼容构造(阶段 A 调用方)。 */
        public SearchBody(String query, Integer topK) {
            this(query, topK, null, null, null, null, null);
        }

        /** 兼容构造(阶段 B 初版:仅库/文档)。 */
        public SearchBody(String query, Integer topK, Long baseId, List<Long> docIds) {
            this(query, topK, baseId, docIds, null, null, null);
        }
    }

    /** POST /api/rag/bases 与 PATCH /api/rag/bases/{id} 请求体。 */
    public record BaseRequest(String name, String description) {
    }

    /** POST /api/rag/docs/{id}/enabled 请求体。 */
    public record EnabledRequest(boolean enabled) {
    }

    /** POST /api/rag/docs/{id}/base 请求体(阶段 B 移库)。 */
    public record BaseMoveRequest(Long baseId) {
    }

    /** PATCH /api/rag/chunks/{id} 与 POST /api/rag/docs/{id}/chunks 请求体(阶段 D)。 */
    public record ChunkContentRequest(String content) {
    }

    /** PUT /api/rag/bases/{id}/retrieval-config 请求体(阶段 D;config 为 JSON 串)。 */
    public record RetrievalConfigRequest(String config) {
    }

    /** file-service 预览响应子集(ApiResponse 信封的 {@code data})。 */
    public record FilePreviewBody(Long fileId, String type, String textContent, String name, String size,
                                  String extractStatus, String extractWarning, String extractError) {
    }

}
