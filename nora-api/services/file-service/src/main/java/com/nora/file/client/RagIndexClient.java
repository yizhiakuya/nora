package com.nora.file.client;

import java.nio.charset.StandardCharsets;

import com.nora.file.api.FileItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fire-and-forget REST client that asks rag-service to index a file.
 * Failures are logged and swallowed so they never affect the caller.
 */
@Component
public class RagIndexClient {

    private static final Logger log = LoggerFactory.getLogger(RagIndexClient.class);

    private final String ragBaseUrl;

    public RagIndexClient(@Value("${nora.rag.base-url:http://localhost:8082}") String ragBaseUrl) {
        this.ragBaseUrl = ragBaseUrl;
    }

    /**
     * Asynchronously POSTs {@code {fileId}} to {@code {ragBaseUrl}/api/rag/index}.
     * Runs on the Spring async executor; any error is only logged.
     *
     * @param file the file to index
     */
    @Async
    public void triggerIndexAsync(FileItem file) {
        try {
            RestClient.create(ragBaseUrl)
                    .post()
                    .uri("/api/rag/index")
                    .body(new IndexRequest(file.id()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("rag-service rejected index request for file {} (status {}): {}",
                                file.id(), res.getStatusCode(), body);
                    })
                    .toBodilessEntity();
            log.info("Triggered rag-service indexing for file {}", file.id());
        } catch (Exception ex) {
            // fire-and-forget: rag-service being down must never break the caller
            log.error("Failed to trigger rag-service indexing for file {}: {}", file.id(), ex.getMessage());
        }
    }

    /** Request body for {@code POST /api/rag/index}. */
    record IndexRequest(Long fileId) {
    }
}
