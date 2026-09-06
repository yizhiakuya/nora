package com.nora.rag.config;

import com.nora.common.http.ProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Bean wiring for rag-service: embedding settings, the embedding client,
 * and the RestClient used to call file-service.
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, FileServiceProperties.class, ProxyProperties.class})
public class RagConfig {

    /**
     * HTTP client for file-service calls (text preview for indexing, indexed callback).
     *
     * @param fileServiceBaseUrl base URL, e.g. http://localhost:8081
     */
    @Bean
    public RestClient fileServiceRestClient(FileServiceProperties fileServiceProperties) {
        return RestClient.builder()
                .baseUrl(fileServiceProperties.baseUrl())
                .build();
    }
}
