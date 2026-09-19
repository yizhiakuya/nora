package com.nora.automation.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.common.exception.BusinessException;
import com.nora.common.notification.NotificationPublisher;

/**
 * 自动化规则的 CRUD + 执行({@code schema_automation})。
 * 每次运行(手动或计划)都落 {@code execution_record}。
 */
@Service
public class AutomationService {

    private final JdbcTemplate jdbcTemplate;
    private final ActionExecutor actionExecutor;
    private final ObjectMapper objectMapper;
    /** 通知事件发布(可空:测试构造不接;Kafka 不可达时静默降级) */
    private final NotificationPublisher notificationPublisher;
    /** 正在执行中的规则 ID:agent 动作可跑数分钟,防止调度/手动重复触发同一规则 */
    private final Set<Long> runningRules = ConcurrentHashMap.newKeySet();

    public AutomationService(JdbcTemplate jdbcTemplate, ActionExecutor actionExecutor,
                             ObjectMapper objectMapper) {
        this(jdbcTemplate, actionExecutor, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AutomationService(JdbcTemplate jdbcTemplate, ActionExecutor actionExecutor,
                             ObjectMapper objectMapper,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             NotificationPublisher notificationPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.actionExecutor = actionExecutor;
        this.objectMapper = objectMapper;
        this.notificationPublisher = notificationPublisher;
    }

    /** 列出规则,最新在前(前端 AutomationRule[])。 */
    public List<RuleView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, trigger_type, trigger_expr, action, enabled, status, last_run_at FROM automation_rule WHERE deleted_at IS NULL ORDER BY id DESC",
                (rs, rowNum) -> new RuleView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("trigger_type"),
                        rs.getString("trigger_expr"),
                        rs.getString("action"),
                        rs.getBoolean("enabled"),
                        rs.getString("status"),
                        rs.getTimestamp("last_run_at")));
    }

    /**
     * Creates a rule. 动作二选一:{@code actionType="agent"} + {@code prompt}
     * (自然语言指令,由 agent-service 执行),或默认 SQL + {@code sql}。
     */
    public RuleView create(String name, String triggerType, String actionType, String sql, String prompt) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        String type = triggerType == null ? "manual" : triggerType;
        if (!List.of("manual", "daily", "weekly", "file", "error").contains(type)) {
            throw new BusinessException(400, "invalid trigger type: " + type);
        }
        boolean agentAction = "agent".equals(actionType);
        if (agentAction) {
            if (prompt == null || prompt.isBlank()) {
                throw new BusinessException(400, "prompt is required for agent actions");
            }
        } else if (sql == null || sql.isBlank()) {
            throw new BusinessException(400, "sql action is required");
        }
        String label = triggerLabel(type);
        String actionJson = agentAction
                ? actionExecutor.agentAction(prompt.trim())
                : actionExecutor.sqlAction(sql);
        jdbcTemplate.update(
                "INSERT INTO automation_rule (name, trigger_type, trigger_expr, action, enabled, status) VALUES (?, ?, ?, ?::jsonb, true, 'active')",
                name.trim(), type, label, actionJson);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM automation_rule WHERE name = ? AND deleted_at IS NULL ORDER BY id DESC LIMIT 1", Long.class, name.trim());
        return get(id);
    }

    /** 启用/停用规则。 */
    public RuleView toggle(long id) {
        RuleView current = get(id);
        boolean next = !current.enabled();
        jdbcTemplate.update(
                "UPDATE automation_rule SET enabled = ?, status = ? WHERE id = ? AND deleted_at IS NULL",
                next, next ? "active" : "paused", id);
        return get(id);
    }

    /** 软删规则(行保留;执行历史留在表里)。 */
    public boolean delete(long id) {
        return jdbcTemplate.update(
                "UPDATE automation_rule SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id) > 0;
    }

    /**
     * 立即运行规则(手动触发):执行动作并落一条带 status/detail 的执行记录。
     *
     * @return 执行记录视图
     */
    public ExecutionView runNow(long id) {
        RuleView rule = get(id);
        return execute(rule);
    }

    /** 手动运行与调度器共用的执行路径。 */
    public ExecutionView execute(RuleView rule) {
        // 同一规则不并发执行:agent 动作一次可跑数分钟,期间调度器每分钟都会
        // 重新扫到这条规则,不挡会重复烧 LLM token。跳过时返回最近一条记录。
        if (!runningRules.add(rule.id())) {
            return latestExecution(rule.id());
        }
        try {
            long start = System.currentTimeMillis();
            String detail = actionExecutor.execute(rule.actionJson());
            long duration = System.currentTimeMillis() - start;
            boolean ok = !detail.startsWith("ERROR");
            jdbcTemplate.update(
                    "INSERT INTO execution_record (rule_id, duration_ms, status, detail) VALUES (?, ?, ?, ?)",
                    rule.id(), duration, ok ? "success" : "failed", detail);
            jdbcTemplate.update(
                    "UPDATE automation_rule SET last_run_at = now(), status = ? WHERE id = ? AND deleted_at IS NULL",
                    ok ? "active" : "error", rule.id());
            // 通知事件(Kafka,2026-09-19):自动任务执行完成/失败——用户在任何页面
            // 都能从通知中心看到(此前只有页面级轮询,切页就丢)。发布失败静默,
            // 不影响执行记录落库。
            if (notificationPublisher != null) {
                notificationPublisher.publish(
                        ok ? "taskDone" : "taskFail",
                        ok ? "任务执行完成" : "任务执行失败",
                        "自动任务「" + rule.name() + "」" + (ok ? "执行成功" : "执行失败")
                                + ",耗时 " + String.format("%.1fs", duration / 1000.0) + "。");
            }
            return latestExecution(rule.id());
        } finally {
            runningRules.remove(rule.id());
        }
    }

    /** 全部规则的最新执行(前端 ExecutionRecord[])。 */
    public List<ExecutionView> listExecutions(int limit) {
        return jdbcTemplate.query(
                "SELECT e.id, e.rule_id, r.name AS rule_name, e.duration_ms, e.status, e.detail, e.started_at "
                        + "FROM execution_record e JOIN automation_rule r ON r.id = e.rule_id "
                        + "ORDER BY e.started_at DESC, e.id DESC LIMIT ?",
                (rs, rowNum) -> new ExecutionView(
                        rs.getLong("id"),
                        rs.getLong("rule_id"),
                        rs.getString("rule_name"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getString("status"),
                        rs.getString("detail"),
                        rs.getTimestamp("started_at")),
                limit);
    }

    /** 计划扫描:运行窗口已到的启用 daily/weekly 规则。 */
    public int runDueScheduled() {
        int ran = 0;
        for (RuleView rule : list()) {
            if (!rule.enabled() || !List.of("daily", "weekly").contains(rule.triggerType())) {
                continue;
            }
            // v1 节律:不追踪"每服务进程每窗口只触发一次"——调度器每分钟调用,
            // 当 last_run 早于今天(daily)或早于 7 天前(weekly)时触发。
            java.sql.Timestamp last = rule.lastRunAt();
            boolean due = switch (rule.triggerType()) {
                case "daily" -> last == null || last.before(todayMinus(0));
                case "weekly" -> last == null || last.before(todayMinus(6));
                default -> false;
            };
            if (due) {
                execute(rule);
                ran++;
            }
        }
        return ran;
    }

    private java.sql.Timestamp todayMinus(int days) {
        return java.sql.Timestamp.valueOf(
                java.time.LocalDate.now().minusDays(days).atStartOfDay());
    }

    private RuleView get(long id) {
        List<RuleView> rows = jdbcTemplate.query(
                "SELECT id, name, trigger_type, trigger_expr, action, enabled, status, last_run_at FROM automation_rule WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new RuleView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("trigger_type"),
                        rs.getString("trigger_expr"),
                        rs.getString("action"),
                        rs.getBoolean("enabled"),
                        rs.getString("status"),
                        rs.getTimestamp("last_run_at")),
                id);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "rule not found: " + id);
        }
        return rows.get(0);
    }

    private ExecutionView latestExecution(long ruleId) {
        List<ExecutionView> rows = jdbcTemplate.query(
                "SELECT e.id, e.rule_id, r.name AS rule_name, e.duration_ms, e.status, e.detail, e.started_at "
                        + "FROM execution_record e JOIN automation_rule r ON r.id = e.rule_id "
                        + "WHERE e.rule_id = ? ORDER BY e.started_at DESC, e.id DESC LIMIT 1",
                (rs, rowNum) -> new ExecutionView(
                        rs.getLong("id"),
                        rs.getLong("rule_id"),
                        rs.getString("rule_name"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getString("status"),
                        rs.getString("detail"),
                        rs.getTimestamp("started_at")),
                ruleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String triggerLabel(String type) {
        return switch (type) {
            case "daily" -> "每日定时";
            case "weekly" -> "每周定时";
            case "file" -> "文件上传时";
            case "error" -> "服务异常时";
            default -> "手动触发";
        };
    }

    /** 前端消费的规则行。 */
    public record RuleView(
            long id,
            String name,
            String triggerType,
            String triggerLabel,
            String actionJson,
            boolean enabled,
            String status,
            java.sql.Timestamp lastRunAt) {
    }

    /** 前端消费的执行行(ruleId 供「重试」定位规则)。 */
    public record ExecutionView(
            long id,
            long ruleId,
            String ruleName,
            Long durationMs,
            String status,
            String detail,
            java.sql.Timestamp startedAt) {
    }
}
