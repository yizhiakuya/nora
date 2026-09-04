package com.nora.automation.api;

/**
 * Dubbo contract for automation-service (per architecture-v2.md section 5.1).
 *
 * <p>Provider: services/automation-service (Quartz scheduling, event triggers,
 * action executor). Consumers: agent-service ({@code NoraTools#createAutomation}
 * ReAct tool) and gateway-routed REST callers.
 *
 * <p>Implementations are registered to Nacos via Dubbo and injected on the
 * consumer side with {@code @DubboReference}.
 */
public interface AutomationService {

    /**
     * Runs the automation rule with the given id exactly once.
     *
     * @param ruleId id of the automation rule to execute
     * @return execution record with status, duration and outcome detail
     */
    ExecutionRecord run(Long ruleId);

    /**
     * Registers a new automation rule.
     *
     * <p>Per the ACI design (architecture-v2.md section 4.8.3), the return value
     * is a structured confirmation (ruleId + name), not execution details.
     *
     * @param name    human-readable rule name
     * @param trigger trigger condition (cron expression or event pattern)
     * @param action  action to execute when the trigger fires
     * @return confirmation string containing the created ruleId and name
     */
    String addRule(String name, String trigger, String action);
}
