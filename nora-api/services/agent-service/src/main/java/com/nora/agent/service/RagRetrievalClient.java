package com.nora.agent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.nora.agent.dto.CitationDto;

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
     * 在知识库中检索与查询相关的块。
     *
     * @param query 自然语言查询
     * @param topK  最大块数
     * @return 带分引用,最优在前;rag-service 不可用时为空
     */
    public List<CitationDto> search(String query, int topK) {
        try {
            Envelope<List<CitationDto>> envelope = restClient.post()
                    .uri("/api/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SearchBody(query, topK))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                log.warn("rag-service search returned no usable payload for query '{}' (envelope={})",
                        query, envelope == null ? "null" : envelope.code());
                return List.of();
            }
            return envelope.data();
        } catch (Exception e) {
            log.warn("rag-service search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /** POST /api/rag/search 请求体。 */
    record SearchBody(String query, Integer topK) {
    }

    /** rag-service 返回的 ApiResponse 信封。 */
    record Envelope<T>(int code, T data, String message) {
    }

    /**
     * 拉取一篇知识库文档的 chunks(对话框 @ 引用注入用)。
     * GET /api/rag/docs/{id};失败/不存在返回 null(调用方降级)。
     */
    public DocChunks docChunks(long docId) {
        try {
            Envelope<DocDetailPayload> envelope = restClient.get()
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
