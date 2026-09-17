package com.nora.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.nora.common.http.EnvelopeErrorHandler;
import com.nora.common.http.ProxyProperties;

/**
 * rag-service 的 Bean 装配:嵌入设置、嵌入客户端与调用 file-service 的 RestClient。
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, FileServiceProperties.class,
        ProxyProperties.class, RetrievalProperties.class})
public class RagConfig {

    /**
     * 调用 file-service(索引用文本预览、indexed 回调)的 HTTP 客户端。
     *
     * @param fileServiceBaseUrl base URL,如 http://localhost:8081
     */
    @Bean
    public RestClient fileServiceRestClient(FileServiceProperties fileServiceProperties) {
        return RestClient.builder()
                .baseUrl(fileServiceProperties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }
}
