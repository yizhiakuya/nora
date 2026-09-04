package com.nora.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Bean wiring for agent-service: provider settings and the RestClient
 * used to call rag-service.
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, RagServiceProperties.class})
public class AgentConfig {

    /**
     * HTTP client for rag-service calls (knowledge retrieval).
     *
     * @param ragServiceProperties rag-service settings
     * @return RestClient bound to rag-service base URL
     */
    @Bean
    public RestClient ragServiceRestClient(RagServiceProperties ragServiceProperties) {
        return RestClient.builder()
                .baseUrl(ragServiceProperties.baseUrl())
                .build();
    }
}
