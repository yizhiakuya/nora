package com.nora.automation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.nora.automation.service.AutomationService;
import com.nora.common.http.EnvelopeErrorHandler;

/**
 * automation-service 的 Bean 装配:调用 datasource-service 的 RestClient
 * 与触发到点 daily/weekly 规则的调度器。
 */
@Configuration
public class AutomationConfig {

    /**
     * 调用 datasource-service(SQL 动作执行器)的 HTTP 客户端。
     *
     * @param baseUrl 来自 {@code nora.datasource.base-url} 的 datasource-service 源
     * @return 绑定 datasource-service base URL 的 RestClient
     */
    @Bean
    public RestClient datasourceServiceRestClient(
            @Value("${nora.datasource.base-url:http://localhost:8084}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl)
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create()).build();
    }

    /**
     * agent-service 调用客户端(agent 动作执行器)。读超时必须覆盖完整的一次
     * agent 运行(RAG + 工具循环),比默认值长。
     *
     * @param baseUrl agent-service 地址,来自 {@code nora.agent.base-url}
     * @return 绑定到 agent-service 基础 URL 的 RestClient
     */
    @Bean
    public RestClient agentServiceRestClient(
            @Value("${nora.agent.base-url:http://localhost:8083}") String baseUrl) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // 300s(2026-09-21 放宽):agent 轮次可跑数分钟(fetch_media 批量下载、
        // 长工具循环)——180s 对媒体类任务偏紧。超时后的错误消息会明确
        // "结果未知、agent 可能仍在跑"(见 ActionExecutor),不冒充失败
        factory.setReadTimeout(300_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, EnvelopeErrorHandler.create()).build();
    }

    /** 每分钟触发到点规则。 */
    @Component
    static class ScheduledRuleRunner {

        private final AutomationService automationService;

        ScheduledRuleRunner(AutomationService automationService) {
            this.automationService = automationService;
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
        public void runDue() {
            try {
                int ran = automationService.runDueScheduled();
                if (ran > 0) {
                    org.slf4j.LoggerFactory.getLogger(ScheduledRuleRunner.class)
                            .info("scheduler fired {} automation rule(s)", ran);
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(ScheduledRuleRunner.class)
                        .warn("scheduled automation scan failed: {}", e.getMessage());
            }
        }
    }
}
