package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 file-service 设置({@code nora.file.*})。
 */
@ConfigurationProperties(prefix = "nora.file")
public record FileServiceProperties(String baseUrl) {

    public static final String DEFAULT_BASE_URL = "http://localhost:18081";

    public FileServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
