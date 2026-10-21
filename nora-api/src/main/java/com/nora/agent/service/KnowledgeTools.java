package com.nora.agent.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.nora.rag.service.ChunkingService;
import com.nora.rag.service.KnowledgeDocService;
import com.nora.rag.service.KnowledgeIndexingService;
import com.nora.rag.service.RetrievalService.RetrievalScope;

@Service
public class KnowledgeTools {
    private final KnowledgeDocService knowledge;
    private final KnowledgeIndexingService indexing;
    private final KnowledgeRetrieval retrieval;

    public KnowledgeTools(KnowledgeDocService knowledge, KnowledgeIndexingService indexing, KnowledgeRetrieval retrieval) {
        this.knowledge = knowledge;
        this.indexing = indexing;
        this.retrieval = retrieval;
    }

    public String search(String query, int topK, Long baseId, List<Long> docIds, List<String> sources) {
        if (query == null || query.isBlank()) return "ERROR: query is required";
        var result = retrieval.searchWithStatus(query, Math.clamp(topK, 1, 20), new RetrievalScope(baseId, docIds, sources, null, null));
        if (result.unavailable()) return "ERROR: 知识库检索暂不可用，这是服务故障，不是资料里没有。" + result.degradedReason();
        if (result.resultsOrEmpty().isEmpty()) return (result.degraded() ? "(部分检索通道降级)" : "(无命中)")
                + "未找到相关片段，可换关键词重试，或用 manage_knowledge action=list 确认文档已入库";
        StringBuilder out = new StringBuilder("知识库命中(共 " + result.resultsOrEmpty().size() + " 块，最优在前"
                + (result.degraded() ? ";部分检索通道降级，结果可能不全" : "") + "):\n");
        for (var hit : result.resultsOrEmpty()) {
            out.append("- [").append(hit.docName()).append('#').append(hit.chunkIndex());
            if (hit.chunkId() != null) out.append(" chunkId=").append(hit.chunkId());
            out.append("] 分=").append(String.format(java.util.Locale.ROOT, "%.3f", hit.score()));
            if (hit.matchChannel() != null) out.append(" 命中=").append(hit.matchChannel());
            out.append("\n  ").append(Texts.abbreviate(hit.evidence(), 1500)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    public String list(String filter) {
        var docs = knowledge.listDocs().stream().filter(row -> filter == null || filter.isBlank()
                || row.name().toLowerCase(java.util.Locale.ROOT).contains(filter.toLowerCase(java.util.Locale.ROOT))).toList();
        if (docs.isEmpty()) return "(没有匹配的知识库文档)";
        return "知识库文档(共 " + docs.size() + " 篇):\n" + docs.stream().map(row -> "id=" + row.id()
                + " | " + row.name() + " | 来源=" + row.source() + " | 块=" + row.chunks()
                + " | 状态=" + row.status() + " | 更新=" + row.updatedAt()).collect(java.util.stream.Collectors.joining("\n"));
    }

    public String indexFile(long fileId, String name) {
        var doc = indexing.indexFile(fileId, name, ChunkingService.ChunkConfig.DEFAULT, null);
        return "已索引为知识库文档 id=" + doc.id() + " name=" + doc.name() + " 块数=" + doc.chunks()
                + " 状态=" + doc.status() + "。现在可用 search_knowledge 检索它";
    }

    public String remove(long docId) {
        return knowledge.deleteDoc(docId) ? "已删除知识库文档 id=" + docId + "(文件中心原文件不受影响)"
                : "ERROR: 文档不存在: " + docId;
    }

    public String reindex(long docId) {
        var doc = indexing.reindex(docId);
        return "已重建文档 id=" + docId + "(" + doc.name() + ")的向量，块数=" + doc.chunks() + " 状态=" + doc.status();
    }

    public String stats() {
        var stats = knowledge.getIndexStats();
        return "知识库统计:文档 " + stats.totalDocs() + " 篇 / 块 " + stats.totalChunks() + " 个 / 向量维度 "
                + stats.vectorDim() + " / 嵌入模型 " + stats.model() + " / 处理中 " + stats.pendingDocs()
                + " / 最后更新 " + stats.lastUpdate() + (stats.vectorReady() ? "" : " / ⚠ 向量组件未就绪");
    }

    public String listBases() {
        var bases = knowledge.listBases();
        if (bases.isEmpty()) return "(无资料库)";
        return bases.stream().map(row -> "- id=" + row.id() + " 「" + row.name() + "」" + (row.isDefault() ? " [默认]" : "")
                + " 文档 " + row.docCount() + " 篇" + (row.description() == null ? "" : " — " + row.description()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public String setEnabled(long docId, boolean enabled) {
        var doc = knowledge.setEnabled(docId, enabled);
        return doc == null ? "ERROR: 文档不存在: " + docId : "文档 id=" + docId + " 已"
                + (enabled ? "启用" : "停用(退出检索，数据保留)") + ":「" + doc.name() + "」";
    }
}
