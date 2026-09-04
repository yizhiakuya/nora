package com.nora.agent.service;

import com.nora.agent.dto.CitationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Retrieves knowledge chunks from rag-service ({@code POST /api/rag/search}).
 * Retrieval is best-effort: an unreachable rag-service yields an empty list
 * so the chat keeps working without RAG context.
 */
@Service
public class RagRetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalClient.class);

    private final RestClient restClient;

    public RagRetrievalClient(RestClient ragServiceRestClient) {
        this.restClient = ragServiceRestClient;
    }

    /**
     * Searches the knowledge base for chunks relevant to the query.
     *
     * @param query natural-language query
     * @param topK  maximum number of chunks
     * @return scored citations, best first; empty when rag-service is unavailable
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

    /** POST /api/rag/search body. */
    record SearchBody(String query, Integer topK) {
    }

    /** ApiResponse envelope as returned by rag-service. */
    record Envelope<T>(int code, T data, String message) {
    }
}
