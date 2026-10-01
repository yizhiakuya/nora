package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 datasource-service 设置({@code nora.datasource.*})。
 */
@ConfigurationProperties(prefix = "nora.datasource")
public record DatasourceServiceProperties(String baseUrl) {

    public static final String DEFAULT_BASE_URL = "http://localhost:18084";

    public DatasourceServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
