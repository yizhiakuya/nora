package com.nora.rag.controller;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final com.nora.rag.service.KnowledgeIndexingService fileIndexing;
    private final NotificationPublisher notificationPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RagController(RetrievalService retrievalService, KnowledgeDocService knowledgeDocService,
                         IndexingService indexingService, ChunkingService chunkingService,
                         com.nora.rag.service.KnowledgeIndexingService fileIndexing,
                         com.fasterxml.jackson.databind.ObjectMapper objectMapper, NotificationPublisher notificationPublisher) {
        this.retrievalService = retrievalService;
        this.knowledgeDocService = knowledgeDocService;
        this.indexingService = indexingService;
        this.chunkingService = chunkingService;
        this.fileIndexing = fileIndexing;
        this.objectMapper = objectMapper;
        this.notificationPublisher = notificationPublisher;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping("/index")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> index(@RequestBody IndexRequest request) {
        if (request.fileId() == null) throw BusinessException.validation("fileId is required");
        return ApiResponse.ok(fileIndexing.indexFile(request.fileId(), request.name(), new ChunkingService.ChunkConfig(
                request.chunkMode(), request.chunkSize(), request.overlap(), request.separator()), request.baseId()));
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

    /**
     * POST /api/rag/index/text —— 按展示名索引原始文本
     * (按名索引;重存同名会替换其分块)。
     * Consumed by the chat "保存到知识库" action so saved answers survive
     * 刷新后仍在,且跨设备可检索。
     *
     * <p><b>来源标记(A2,2026-09-27)</b>:请求可带 {@code source}(chat / text,
     * 默认 text)——对话保存传 {@code chat},知识库页显示「对话产出」而非
     * 「文本保存」(评审报告 §6:AI 生成内容应明确标记,避免以后检索到旧结论
     * 时误以为它就是原始资料)。评测脚本(rag-eval.py)不传,行为不变。
     */
    @PostMapping("/index/text")
    public ApiResponse<KnowledgeDocService.KnowledgeDocView> indexText(@RequestBody TextIndexRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        String source = request.source() == null || request.source().isBlank() ? "text" : request.source().trim();
        if (!List.of("text", "chat").contains(source)) {
            throw new BusinessException(400, "source 只允许 text / chat,收到: " + source);
        }
        String name = (request.name() == null || request.name().isBlank())
                ? "对话保存 " + java.time.LocalDate.now() : request.name();
        // 分段配置(阶段 B 模式 / 阶段 D 完整参数):chunkSize/overlap/separator 可选
        ChunkingService.ChunkConfig config = new ChunkingService.ChunkConfig(
                request.chunkMode(), request.chunkSize(), request.overlap(), request.separator());
        long docId = indexingService.indexDocument(name, source, null,
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
        return ApiResponse.ok(fileIndexing.reindex(id));
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

    /** 删除结果;{@code deleted} 为实际删除的行数。 */
    public record DeleteResult(int deleted) {
    }

    /** POST /api/rag/index/text 请求体(阶段 B:可带 chunkMode 与 baseId)。 */
    public record TextIndexRequest(String name, String text, String chunkMode, Long baseId,
                                   Integer chunkSize, Integer overlap, String separator,
                                   /** A2:来源标记(text 默认 / chat 对话保存)。 */
                                   String source) {
        /** 兼容构造(阶段 A 调用方)。 */
        public TextIndexRequest(String name, String text) {
            this(name, text, null, null, null, null, null, null);
        }

        /** 兼容构造(阶段 B 形态:模式 + 库)。 */
        public TextIndexRequest(String name, String text, String chunkMode, Long baseId) {
            this(name, text, chunkMode, baseId, null, null, null, null);
        }

        /** 兼容构造(阶段 D 形态:完整分段参数;source 缺省 text)。 */
        public TextIndexRequest(String name, String text, String chunkMode, Long baseId,
                                Integer chunkSize, Integer overlap, String separator) {
            this(name, text, chunkMode, baseId, chunkSize, overlap, separator, null);
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

}
