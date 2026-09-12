package com.nora.agent.config;

import com.nora.common.http.ProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.nora.common.http.EnvelopeErrorHandler;
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
        EnvServiceProperties.class,
        FileServiceProperties.class,
        ProxyProperties.class})
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
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
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
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
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
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }

    /**
     * HTTP client for file-service calls (workbench file listing/preview).
     *
     * @param properties file-service settings
     * @return RestClient bound to file-service base URL
     */
    @Bean
    public RestClient fileServiceRestClient(FileServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }
}
