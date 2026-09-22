package com.nora.agent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.nora.agent.dto.CitationDto;
import com.nora.common.response.ApiResponse;

/**
 * 从 rag-service({@code POST /api/rag/search})检索知识块。
 * 检索是尽力而为:rag-service 不可达时返回空列表,对话无 RAG 上下文仍可继续。
 */
@Service
public class RagRetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalClient.class);

    private final RestClient restClient;

    public RagRetrievalClient(RestClient ragServiceRestClient) {
        this.restClient = ragServiceRestClient;
    }

    /**
     * 在知识库中检索与查询相关的块(2026-09-22 阶段 A:带通道状态)。
     *
     * <p>此前检索异常折叠为空列表——「检索坏了」表现为「资料里没有」。
     * 现在返回 {@link RetrievalPayload}:status + 通道状态 + 命中;
     * 调用方(编排层)可把 degraded/unavailable 显式下发。
     *
     * @param query 自然语言查询
     * @param topK  最大块数
     * @return 结果集;rag-service 不可用时 status=unavailable(绝不静默为空)
     */
    public RetrievalPayload searchWithStatus(String query, int topK) {
        try {
            ApiResponse<RetrievalPayload> envelope = restClient.post()
                    .uri("/api/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SearchBody(query, topK))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                log.warn("rag-service search returned no usable payload for query '{}' (envelope={})",
                        query, envelope == null ? "null" : envelope.code());
                return RetrievalPayload.unavailable("rag-service 返回异常: "
                        + (envelope == null ? "空响应" : envelope.message()));
            }
            return envelope.data();
        } catch (Exception e) {
            log.warn("rag-service search failed for query '{}': {}", query, e.getMessage());
            return RetrievalPayload.unavailable("rag-service 不可达: " + e.getMessage());
        }
    }

    /**
     * 兼容入口(旧调用方):只要命中列表;异常时为空。
     */
    public List<CitationDto> search(String query, int topK) {
        return searchWithStatus(query, topK).resultsOrEmpty();
    }

    /** POST /api/rag/search 请求体。 */
    record SearchBody(String query, Integer topK) {
    }

    /**
     * rag-service 检索结果载荷(RetrievalOutcome 的消费侧镜像)。
     *
     * @param status  ok/no_match/degraded/unavailable
     * @param results 命中(最优在前)
     * @param vector  向量通道状态(ok/error 原因)
     * @param keyword 关键词通道状态
     */
    public record RetrievalPayload(
            String status,
            List<CitationDto> results,
            ChannelStatus vector,
            ChannelStatus keyword
    ) {
        /** 单通道状态镜像。 */
        public record ChannelStatus(boolean ok, String error) {
        }

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

    /**
     * 拉取一篇知识库文档的 chunks(对话框 @ 引用注入用)。
     * GET /api/rag/docs/{id};失败/不存在返回 null(调用方降级)。
     */
    public DocChunks docChunks(long docId) {
        try {
            ApiResponse<DocDetailPayload> envelope = restClient.get()
                    .uri("/api/rag/docs/{id}", docId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null
                    || envelope.data().chunks() == null) {
                log.warn("rag-service doc detail returned no usable payload for doc {} (envelope={})",
                        docId, envelope == null ? "null" : envelope.code());
                return null;
            }
            DocDetailPayload d = envelope.data();
            String name = d.doc() != null && d.doc().name() != null ? d.doc().name() : ("doc-" + docId);
            List<DocChunk> chunks = d.chunks().stream()
                    .filter(c -> c.content() != null && !c.content().isBlank())
                    .map(c -> new DocChunk(c.chunkIndex(), c.content()))
                    .toList();
            return new DocChunks(name, chunks);
        } catch (Exception e) {
            log.warn("rag-service doc detail failed for doc {}: {}", docId, e.getMessage());
            return null;
        }
    }

    /** 一篇文档的名称与 chunk 正文(仅注入所需字段)。 */
    public record DocChunks(String docName, List<DocChunk> chunks) {
    }

    public record DocChunk(int chunkIndex, String content) {
    }

    /** GET /api/rag/docs/{id} 响应(仅取注入所需字段;其余字段忽略)。 */
    record DocDetailPayload(DocInfo doc, List<ChunkPayload> chunks) {
    }

    record DocInfo(String name) {
    }

    record ChunkPayload(int chunkIndex, String content) {
    }
}
