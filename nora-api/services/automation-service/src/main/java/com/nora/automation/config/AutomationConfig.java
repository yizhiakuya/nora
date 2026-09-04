package com.nora.automation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.nora.automation.service.AutomationService;

/**
 * Bean wiring for automation-service: the RestClient for datasource-service
 * calls and the scheduler that fires due daily/weekly rules.
 */
@Configuration
public class AutomationConfig {

    /**
     * HTTP client for datasource-service calls (SQL action executor).
     *
     * @param baseUrl datasource-service origin from {@code nora.datasource.base-url}
     * @return RestClient bound to the datasource-service base URL
     */
    @Bean
    public RestClient datasourceServiceRestClient(
            @Value("${nora.datasource.base-url:http://localhost:8084}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Fires due rules every minute. */
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
