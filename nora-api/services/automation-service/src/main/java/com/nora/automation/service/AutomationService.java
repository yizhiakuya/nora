package com.nora.automation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CRUD + execution for automation rules ({@code schema_automation}).
 * Every run (manual or scheduled) lands in {@code execution_record}.
 */
@Service
public class AutomationService {

    private final JdbcTemplate jdbcTemplate;
    private final ActionExecutor actionExecutor;
    private final ObjectMapper objectMapper;

    public AutomationService(JdbcTemplate jdbcTemplate, ActionExecutor actionExecutor,
                             ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.actionExecutor = actionExecutor;
        this.objectMapper = objectMapper;
    }

    /** Lists rules, newest first (frontend AutomationRule[]). */
    public List<RuleView> list() {
        return jdbcTemplate.query(
                "SELECT id, name, trigger_type, trigger_expr, action, enabled, status, last_run_at FROM automation_rule ORDER BY id DESC",
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

    /** Creates a rule. v1 action executor is SQL-only. */
    public RuleView create(String name, String triggerType, String sql) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        String type = triggerType == null ? "manual" : triggerType;
        if (!List.of("manual", "daily", "weekly", "file", "error").contains(type)) {
            throw new BusinessException(400, "invalid trigger type: " + type);
        }
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(400, "sql action is required");
        }
        String label = triggerLabel(type);
        jdbcTemplate.update(
                "INSERT INTO automation_rule (name, trigger_type, trigger_expr, action, enabled, status) VALUES (?, ?, ?, ?::jsonb, true, 'active')",
                name.trim(), type, label, actionExecutor.sqlAction(sql));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM automation_rule WHERE name = ? ORDER BY id DESC LIMIT 1", Long.class, name.trim());
        return get(id);
    }

    /** Enables/disables a rule. */
    public RuleView toggle(long id) {
        RuleView current = get(id);
        boolean next = !current.enabled();
        jdbcTemplate.update(
                "UPDATE automation_rule SET enabled = ?, status = ? WHERE id = ?",
                next, next ? "active" : "paused", id);
        return get(id);
    }

    /** Deletes a rule (executions cascade). */
    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM automation_rule WHERE id = ?", id) > 0;
    }

    /**
     * Runs a rule now (manual trigger): executes the action and persists an
     * execution record with status/detail.
     *
     * @return the execution record view
     */
    public ExecutionView runNow(long id) {
        RuleView rule = get(id);
        return execute(rule);
    }

    /** Shared execution path for manual runs and the scheduler. */
    public ExecutionView execute(RuleView rule) {
        long start = System.currentTimeMillis();
        String detail = actionExecutor.execute(rule.actionJson());
        long duration = System.currentTimeMillis() - start;
        boolean ok = !detail.startsWith("ERROR");
        jdbcTemplate.update(
                "INSERT INTO execution_record (rule_id, duration_ms, status, detail) VALUES (?, ?, ?, ?)",
                rule.id(), duration, ok ? "success" : "failed", detail);
        jdbcTemplate.update(
                "UPDATE automation_rule SET last_run_at = now(), status = ? WHERE id = ?",
                ok ? "active" : "error", rule.id());
        return latestExecution(rule.id());
    }

    /** Latest executions across all rules (frontend ExecutionRecord[]). */
    public List<ExecutionView> listExecutions(int limit) {
        return jdbcTemplate.query(
                "SELECT e.id, r.name AS rule_name, e.duration_ms, e.status, e.detail, e.started_at "
                        + "FROM execution_record e JOIN automation_rule r ON r.id = e.rule_id "
                        + "ORDER BY e.started_at DESC, e.id DESC LIMIT ?",
                (rs, rowNum) -> new ExecutionView(
                        rs.getLong("id"),
                        rs.getString("rule_name"),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getString("status"),
                        rs.getString("detail"),
                        rs.getTimestamp("started_at")),
                limit);
    }

    /** Scheduled scan: runs enabled daily/weekly rules whose window has come. */
    public int runDueScheduled() {
        int ran = 0;
        for (RuleView rule : list()) {
            if (!rule.enabled() || !List.of("daily", "weekly").contains(rule.triggerType())) {
                continue;
            }
            // v1 cadence: fire once per service process per rule window is not tracked —
            // the scheduler calls this every minute and fires when last_run predates today
            // (daily) or predates 7 days (weekly).
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
                "SELECT id, name, trigger_type, trigger_expr, action, enabled, status, last_run_at FROM automation_rule WHERE id = ?",
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
                "SELECT e.id, r.name AS rule_name, e.duration_ms, e.status, e.detail, e.started_at "
                        + "FROM execution_record e JOIN automation_rule r ON r.id = e.rule_id "
                        + "WHERE e.rule_id = ? ORDER BY e.started_at DESC, e.id DESC LIMIT 1",
                (rs, rowNum) -> new ExecutionView(
                        rs.getLong("id"),
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

    /** Rule row as consumed by the frontend. */
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

    /** Execution row as consumed by the frontend. */
    public record ExecutionView(
            long id,
            String ruleName,
            Long durationMs,
            String status,
            String detail,
            java.sql.Timestamp startedAt) {
    }
}
