package com.nora.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Downstream file-service settings ({@code nora.file-service.*}).
 *
 * @param baseUrl file-service origin, e.g. {@code http://localhost:8081}
 */
@ConfigurationProperties(prefix = "nora.file-service")
public record FileServiceProperties(String baseUrl) {

    public static final String DEFAULT_BASE_URL = "http://localhost:8081";

    public FileServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
