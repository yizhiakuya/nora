package com.nora.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.nora.common.http.EnvelopeErrorHandler;
import com.nora.common.http.ProxyProperties;

/**
 * agent-service 的 Bean 装配:provider 设置与调用 rag-service 的 RestClient。
 */
@Configuration
@EnableConfigurationProperties({
        LlmProperties.class,
        RagServiceProperties.class,
        DatasourceServiceProperties.class,
        EnvServiceProperties.class,
        FileServiceProperties.class,
        AutomationServiceProperties.class,
        ProxyProperties.class})
public class AgentConfig {

    /**
     * 调用 rag-service(知识检索)的 HTTP 客户端。
     *
     * @param ragServiceProperties rag-service 设置
     * @return 绑定 rag-service base URL 的 RestClient
     */
    @Bean
    public RestClient ragServiceRestClient(RagServiceProperties ragServiceProperties) {
        return RestClient.builder()
                .baseUrl(ragServiceProperties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }

    /**
     * 调用 datasource-service(受控 SQL 执行)的 HTTP 客户端。
     *
     * @param properties datasource-service 设置
     * @return 绑定 datasource-service base URL 的 RestClient
     */
    @Bean
    public RestClient datasourceServiceRestClient(DatasourceServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }

    /**
     * 调用 env-service(服务日志)的 HTTP 客户端。
     *
     * @param properties env-service 设置
     * @return 绑定 env-service base URL 的 RestClient
     */
    @Bean
    public RestClient envServiceRestClient(EnvServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }

    /**
     * 调用 file-service(工作台文件列表/预览)的 HTTP 客户端。
     *
     * @param properties file-service 设置
     * @return 绑定 file-service base URL 的 RestClient
     */
    @Bean
    public RestClient fileServiceRestClient(FileServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }

    /**
     * 调用 automation-service(自动任务 CRUD/执行)的 HTTP 客户端。
     *
     * @param properties automation-service 设置
     * @return 绑定 automation-service base URL 的 RestClient
     */
    @Bean
    public RestClient automationServiceRestClient(AutomationServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create())
                .build();
    }
}
