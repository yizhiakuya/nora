package com.nora.agent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nora.agent.dto.CitationDto;
import com.nora.rag.api.RetrievalOutcome.ChannelStatus;
import com.nora.rag.service.KnowledgeDocService;
import com.nora.rag.service.RetrievalService;
import com.nora.rag.service.RetrievalService.RetrievalScope;

/** 将领域检索结果转成对话引用；故障与无命中分别报告。 */
@Service
public class KnowledgeRetrieval {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrieval.class);
    private final RetrievalService retrieval;
    private final KnowledgeDocService knowledge;

    public KnowledgeRetrieval(RetrievalService retrieval, KnowledgeDocService knowledge) {
        this.retrieval = retrieval;
        this.knowledge = knowledge;
    }

    public RetrievalPayload searchWithStatus(String query, int topK, RetrievalScope scope) {
        try {
            var result = retrieval.searchWithStatus(query, topK, scope == null ? RetrievalScope.ALL : scope);
            var citations = result.results().stream().map(hit -> new CitationDto(hit.docId(), hit.docName(), hit.source(),
                    hit.chunkIndex(), hit.score(), hit.snippet(), hit.chunkId(), hit.matchChannel(),
                    hit.vectorScore(), hit.keywordScore(), hit.content())).toList();
            return new RetrievalPayload(result.status(), citations, result.vector(), result.keyword());
        } catch (RuntimeException e) {
            log.warn("knowledge retrieval failed: {}", e.getMessage());
            return RetrievalPayload.unavailable("知识库检索异常: " + e.getMessage());
        }
    }

    public record RetrievalPayload(
            String status,
            List<CitationDto> results,
            ChannelStatus vector,
            ChannelStatus keyword
    ) {
        static RetrievalPayload unavailable(String reason) {
            return new RetrievalPayload("unavailable", List.of(),
                    new ChannelStatus(false, reason), new ChannelStatus(false, reason));
        }

        /** 命中列表;null 时为空。 */
        public List<CitationDto> resultsOrEmpty() {
            return results == null ? List.of() : results;
        }

        /** 是否全部通道不可用(调用方据此显示「检索暂不可用」)。 */
        public boolean unavailable() {
            return "unavailable".equals(status);
        }

        /** 是否降级(部分通道失败)。 */
        public boolean degraded() {
            return "degraded".equals(status);
        }

        /** 降级原因摘要(用户可见;无降级时 null)。 */
        public String degradedReason() {
            if (!degraded() && !unavailable()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            if (vector != null && !vector.ok() && vector.error() != null) {
                sb.append("向量通道: ").append(vector.error());
            }
            if (keyword != null && !keyword.ok() && keyword.error() != null) {
                if (sb.length() > 0) sb.append(";");
                sb.append("关键词通道: ").append(keyword.error());
            }
            return sb.length() == 0 ? "部分检索通道不可用" : sb.toString();
        }
    }

    public DocChunks docChunks(long docId) {
        var doc = knowledge.getDoc(docId);
        if (doc == null) return null;
        return new DocChunks(doc.name(), knowledge.listChunks(docId).stream()
                .filter(chunk -> chunk.content() != null && !chunk.content().isBlank()).toList());
    }

    public record DocChunks(String docName, List<KnowledgeDocService.ChunkView> chunks) {}
}
