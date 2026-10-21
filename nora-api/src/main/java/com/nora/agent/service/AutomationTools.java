package com.nora.agent.service;

import org.springframework.stereotype.Service;

import com.nora.automation.service.AutomationService;

@Service
public class AutomationTools {
    private final AutomationService automation;

    public AutomationTools(AutomationService automation) { this.automation = automation; }

    public String create(String name, String triggerType, String prompt, String scheduleJson) {
        var rule = automation.create(name, triggerType == null || triggerType.isBlank() ? "manual" : triggerType,
                "agent", null, prompt, scheduleJson, null);
        return "已创建自动任务 id=" + rule.id() + " name=" + rule.name() + " 触发=" + rule.triggerLabel()
                + " enabled=" + rule.enabled() + "。执行历史可在自动任务页查看";
    }

    public String toggle(long id) {
        var rule = automation.toggle(id);
        return "已" + (rule.enabled() ? "启用" : "暂停") + "自动任务 id=" + id + "(" + rule.name() + ")";
    }

    public String remove(long id) {
        return automation.delete(id) ? "已删除自动任务 id=" + id + "(历史执行记录保留)" : "ERROR: 自动任务不存在: " + id;
    }

    public String run(long id) {
        var result = automation.runNow(id);
        return "已运行自动任务 id=" + id + " status=" + result.status() + "\n执行详情:\n"
                + Texts.abbreviate(result.detail(), 3000);
    }

    public String list() {
        var rules = automation.list();
        if (rules.isEmpty()) return "(尚无自动任务)";
        return rules.stream().map(rule -> "id=" + rule.id() + " | " + rule.name() + " | " + rule.triggerLabel()
                + " | " + (rule.enabled() ? "enabled" : "paused") + " | " + rule.status()
                + " | lastRun=" + rule.lastRunAt() + " | nextRun=" + rule.nextRunAt()
                + "\n动作指令: " + rule.actionJson() + "\n日程: " + rule.schedule())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public String executions(int limit) {
        var rows = automation.listExecutions(Math.clamp(limit, 1, 200));
        if (rows.isEmpty()) return "(尚无执行记录)";
        return rows.stream().map(row -> row.startedAt() + " | " + row.ruleName() + " | " + row.status()
                + " | " + row.durationMs() + "ms | " + Texts.abbreviate(row.detail(), 200).replace('\n', ' '))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
