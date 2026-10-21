package com.nora.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.nora.common.exception.BusinessException;
import com.nora.common.notification.NotificationPublisher;
import com.nora.file.api.FileItem;
import com.nora.file.service.FileStorageService;

/** 上传文件索引供 HTTP、工具与后台任务共用；长时间嵌入仍在事务外执行。 */
@Service
public class KnowledgeIndexingService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingService.class);
    private final FileStorageService files;
    private final KnowledgeDocService knowledgeDocService;
    private final IndexingService indexingService;
    private final NotificationPublisher notificationPublisher;

    public KnowledgeIndexingService(FileStorageService files, KnowledgeDocService knowledgeDocService,
                               IndexingService indexingService, NotificationPublisher notificationPublisher) {
        this.files = files;
        this.knowledgeDocService = knowledgeDocService;
        this.indexingService = indexingService;
        this.notificationPublisher = notificationPublisher;
    }

    @Async
    public void indexAsync(FileItem file) {
        try { indexFile(file.id(), null, ChunkingService.ChunkConfig.DEFAULT, null); }
        catch (RuntimeException e) { log.error("file {} indexing failed: {}", file.id(), e.getMessage()); }
    }

    public KnowledgeDocService.KnowledgeDocView reindex(long id) {
        if (knowledgeDocService.getDoc(id) == null) throw BusinessException.notFound("知识库文档不存在: " + id);
        List<String> texts = knowledgeDocService.chunkTexts(id);
        if (texts.isEmpty()) throw new BusinessException(422, "该文档没有可重建的 chunk 正文，请重新上传: " + id);
        indexingService.reindexChunks(id, texts);
        return knowledgeDocService.getDoc(id);
    }

    public KnowledgeDocService.KnowledgeDocView indexFile(long fileId, String requestedName,
                                                      ChunkingService.ChunkConfig config, Long baseId) {
        // 目标资料库校验(F5):显式传了不存在的库要 404,不能静默落默认库
        if (baseId != null) {
            Integer baseExists = knowledgeDocService.countBase(baseId);
            if (baseExists == null || baseExists == 0) {
                throw new BusinessException(404, "目标资料库不存在: " + baseId);
            }
        }

        var preview = files.preview(fileId);
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
            throw new BusinessException(422, "file has no extractable text: " + fileId);
        }

        String name = requestedName != null && !requestedName.isBlank()
                ? requestedName
                : preview.name() != null ? preview.name() : "file-" + fileId;
        String size = preview.size() != null ? preview.size() : "—";

        long docId = indexingService.indexDocument(name, "file", fileId, size, text,
                config, baseId);

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
        if (files.list(List.of(fileId)).isEmpty()) {
            knowledgeDocService.softDeleteByFileId(fileId);
            log.info("file {} deleted during indexing; compensating soft-delete of doc {}", fileId, docId);
            throw BusinessException.conflict("文件在索引期间已被删除,本次索引结果已撤销: " + fileId
                    + "(若仍需索引请先从回收站恢复该文件)");
        }

        files.markIndexed(fileId);

        notificationPublisher.publish("indexed", "文档索引入库",
                    "「" + name + "」已完成清洗与向量化,AI 现在可以检索其内容。");

        return knowledgeDocService.getDoc(docId);
    }
}
