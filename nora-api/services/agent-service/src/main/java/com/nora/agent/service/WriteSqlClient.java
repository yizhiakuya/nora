package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Executes approved write SQL via datasource-service
 * ({@code POST /api/datasources/{id}/execute}). The enforcing guard lives
 * server-side (WriteGuard); this client just relays the result.
 */
@Service
public class WriteSqlClient {

    private static final Logger log = LoggerFactory.getLogger(WriteSqlClient.class);

    private final RestClient restClient;

    public WriteSqlClient(RestClient datasourceServiceRestClient) {
        this.restClient = datasourceServiceRestClient;
    }

    /**
     * @return LLM-friendly rendering ("rows_affected: N ...") or "ERROR: ..."
     */
    public String executeWrite(String sql) {
        try {
            Long connectionId = firstConnectionId();
            if (connectionId == null) {
                return "ERROR: no database connection is configured in the datasource service";
            }
            Envelope<JsonNode> envelope = restClient.post()
                    .uri("/api/datasources/{id}/execute", connectionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new WriteRequest(sql))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null) {
                String message = envelope == null ? "empty response" : envelope.message();
                return "ERROR: " + message;
            }
            int affected = envelope.data().path("rowCount").asInt(-1);
            long durationMs = envelope.data().path("durationMs").asLong(0);
            return "rows_affected: " + affected + " (" + durationMs + "ms)";
        } catch (Exception e) {
            log.warn("write sql failed: {}", e.getMessage());
            return "ERROR: " + (e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    private Long firstConnectionId() {
        try {
            Envelope<JsonNode> envelope = restClient.get()
                    .uri("/api/datasources")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() != 0 || envelope.data() == null
                    || envelope.data().isEmpty() || !envelope.data().isArray()) {
                return null;
            }
            return envelope.data().get(0).path("id").asLong();
        } catch (Exception e) {
            log.warn("datasource list failed: {}", e.getMessage());
            return null;
        }
    }

    /** POST body. */
    record WriteRequest(String sql) {
    }

    /** ApiResponse envelope. */
    record Envelope<T>(int code, T data, String message) {
    }
}
