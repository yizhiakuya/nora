package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 automation-service 设置({@code nora.automation.*})。
 */
@ConfigurationProperties(prefix = "nora.automation")
public record AutomationServiceProperties(String baseUrl) {

    public static final String DEFAULT_BASE_URL = "http://localhost:18086";

    public AutomationServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
