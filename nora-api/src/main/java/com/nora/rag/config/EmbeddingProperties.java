package com.nora.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 嵌入 provider 设置({@code nora.embedding.*})。
 *
 * <p>把 LangChain4j OpenAI 兼容嵌入客户端指向 Jina AI
 * (https://api.jina.ai/v1,模型 jina-embeddings-v3,最多 1024 维)。
 */
@ConfigurationProperties(prefix = "nora.embedding")
public record EmbeddingProperties(
        String apiKey,
        String baseUrl,
        String model,
        Integer dimensions
) {

    /** 嵌入 provider 的 API key;为空则禁用嵌入支持的端点。 */
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

    /** 是否配置了 API key(env NORA_EMBEDDING_API_KEY)。 */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
