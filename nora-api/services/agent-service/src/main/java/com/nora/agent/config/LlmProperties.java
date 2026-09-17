package com.nora.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM provider 设置({@code nora.llm.*}),用于 OpenAI 兼容对话端点
 * (开发环境为 sub2api 网关,任意 OpenAI 兼容中继均可)。
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

    /** 是否配置了 API key(env NORA_LLM_API_KEY)。 */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
