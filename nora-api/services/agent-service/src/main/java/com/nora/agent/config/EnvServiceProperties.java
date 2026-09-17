package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 env-service 设置({@code nora.env.*})。
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
