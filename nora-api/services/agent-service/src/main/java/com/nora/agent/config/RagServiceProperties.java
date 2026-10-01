package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 rag-service 设置({@code nora.rag.*})。
 */
@ConfigurationProperties(prefix = "nora.rag")
public record RagServiceProperties(String baseUrl) {

    public static final String DEFAULT_BASE_URL = "http://localhost:18082";

    public RagServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
