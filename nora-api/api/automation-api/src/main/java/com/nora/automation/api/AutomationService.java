package com.nora.automation.api;

/**
 * automation-service 的 Dubbo 契约(architecture-v2.md 5.1 节)。
 *
 * <p>提供方:services/automation-service(Quartz 调度、事件触发、动作执行器)。
 * 消费方:agent-service({@code NoraTools#createAutomation} ReAct 工具)与经
 * 网关路由的 REST 调用方。
 *
 * <p>实现经 Dubbo 注册到 Nacos,消费方以 {@code @DubboReference} 注入。
 */
public interface AutomationService {

    /**
     * 精确运行一次给定 id 的自动化规则。
     *
     * @param ruleId 要执行的规则 id
     * @return 带 status、duration 与结果 detail 的执行记录
     */
    ExecutionRecord run(Long ruleId);

    /**
     * 注册新的自动化规则。
     *
     * <p>按 ACI 设计(architecture-v2.md 4.8.3 节),返回值是结构化确认
     * (ruleId + name),不是执行细节。
     *
     * @param name    人类可读规则名
     * @param trigger 触发条件(cron 表达式或事件模式)
     * @param action  触发时执行的动作
     * @return 含创建出的 ruleId 与 name 的确认字符串
     */
    String addRule(String name, String trigger, String action);
}
