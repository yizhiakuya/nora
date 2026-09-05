package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Downstream env-service settings ({@code nora.env.*}).
 */
@ConfigurationProperties(prefix = "nora.env")
public record EnvServiceProperties(String baseUrl) {

    public static final String DEFAULT_BASE_URL = "http://localhost:8085";

    public EnvServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
