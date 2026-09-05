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
@EnableConfigurationProperties({
        LlmProperties.class,
        RagServiceProperties.class,
        DatasourceServiceProperties.class,
        EnvServiceProperties.class})
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

    /**
     * HTTP client for datasource-service calls (guarded SQL execution).
     *
     * @param properties datasource-service settings
     * @return RestClient bound to datasource-service base URL
     */
    @Bean
    public RestClient datasourceServiceRestClient(DatasourceServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    /**
     * HTTP client for env-service calls (service logs).
     *
     * @param properties env-service settings
     * @return RestClient bound to env-service base URL
     */
    @Bean
    public RestClient envServiceRestClient(EnvServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }
}
