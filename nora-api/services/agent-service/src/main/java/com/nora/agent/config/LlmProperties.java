package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM provider settings ({@code nora.llm.*}) for the OpenAI-compatible
 * chat endpoint (sub2api gateway in dev, any OpenAI-compatible relay).
 */
@ConfigurationProperties(prefix = "nora.llm")
public record LlmProperties(
        String apiKey,
        String baseUrl,
        String model
) {

    public static final String DEFAULT_BASE_URL = "http://192.168.0.109:28765/v1";
    public static final String DEFAULT_MODEL = "gpt-5.4-mini";

    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    /** Whether an API key is configured (env NORA_LLM_API_KEY). */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
