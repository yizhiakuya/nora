package com.nora.automation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.nora.automation.service.AutomationService;

@Configuration(proxyBeanMethods = false)
public class AutomationConfig {
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
