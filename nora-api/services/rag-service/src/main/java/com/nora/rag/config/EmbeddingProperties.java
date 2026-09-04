package com.nora.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding provider settings ({@code nora.embedding.*}).
 *
 * <p>Points the LangChain4j OpenAI-compatible embedding client at Jina AI
 * (https://api.jina.ai/v1, model jina-embeddings-v3, max 1024 dimensions).
 */
@ConfigurationProperties(prefix = "nora.embedding")
public record EmbeddingProperties(
        String apiKey,
        String baseUrl,
        String model,
        Integer dimensions
) {

    /** API key of the embedding provider; empty disables embedding-backed endpoints. */
    public static final String DEFAULT_BASE_URL = "https://api.jina.ai/v1";
    public static final String DEFAULT_MODEL = "jina-embeddings-v3";
    public static final int DEFAULT_DIMENSIONS = 1024;

    public EmbeddingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (dimensions == null || dimensions <= 0) {
            dimensions = DEFAULT_DIMENSIONS;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    /** Whether an API key is configured (env NORA_EMBEDDING_API_KEY). */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
