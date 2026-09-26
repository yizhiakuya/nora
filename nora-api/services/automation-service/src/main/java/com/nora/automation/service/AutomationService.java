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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AutomationService.class);

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
                "SELECT id, name, trigger_type, trigger_expr, action, enabled, status, last_run_at, schedule, next_run_at, configuration_status FROM automation_rule WHERE deleted_at IS NULL ORDER BY id DESC",
                (rs, rowNum) -> new RuleView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("trigger_type"),
                        rs.getString("trigger_expr"),
                        rs.getString("action"),
                        rs.getBoolean("enabled"),
                        rs.getString("status"),
                        rs.getTimestamp("last_run_at"),
                        rs.getString("schedule"),
                        rs.getTimestamp("next_run_at"),
                        rs.getString("configuration_status")));
    }

    /**
     * Creates a rule(M4-01 扩展)。动作二选一:{@code actionType="agent"} + {@code prompt}
     * (自然语言指令,由 agent-service 执行),或默认 SQL + {@code sql}。
     *
     * <p>日程(M4-01):daily/weekly 必须带 {@code scheduleJson}
     * ({@code {frequency,localTime,dayOfWeek,timezone}});服务端校验并计算
     * nextRunAt(方案 §7.2:新建规则默认第一次在未来计划点执行)。手动规则忽略。
     */
    public RuleView create(String name, String triggerType, String actionType, String sql, String prompt) {
        return create(name, triggerType, actionType, sql, prompt, null);
    }

    public RuleView create(String name, String triggerType, String actionType, String sql, String prompt,
                           String scheduleJson) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "name is required");
        }
        String type = triggerType == null ? "manual" : triggerType;
        if (!List.of("manual", "daily", "weekly").contains(type)) {
            throw new BusinessException(400,
                    "invalid trigger type: " + type + "(file/error 触发方式未接通,请使用 manual/daily/weekly)");
        }
        boolean agentAction = "agent".equals(actionType);
        if (agentAction) {
            if (prompt == null || prompt.isBlank()) {
                throw new BusinessException(400, "prompt is required for agent actions");
            }
        } else if (sql == null || sql.isBlank()) {
            throw new BusinessException(400, "sql action is required");
        }
        // 日程校验与 nextRunAt 计算(M4-01):daily/weekly 必须带完整日程
        String normalizedSchedule = null;
        java.time.LocalDateTime nextRunAt = null;
        if ("daily".equals(type) || "weekly".equals(type)) {
            if (scheduleJson == null || scheduleJson.isBlank()) {
                throw new BusinessException(400,
                        "daily/weekly 规则必须提供 schedule(frequency/localTime/dayOfWeek/timezone)");
            }
            normalizedSchedule = normalizeSchedule(scheduleJson, type);
            nextRunAt = computeNextRunAt(normalizedSchedule);
        }
        String label = triggerLabel(type);
        String actionJson = agentAction
                ? actionExecutor.agentAction(prompt.trim())
                : actionExecutor.sqlAction(sql);
        jdbcTemplate.update(
                "INSERT INTO automation_rule (name, trigger_type, trigger_expr, action, enabled, status, schedule, next_run_at, configuration_status) "
                        + "VALUES (?, ?, ?, ?::jsonb, true, 'active', ?::jsonb, ?, 'ok')",
                name.trim(), type, label, actionJson, normalizedSchedule, nextRunAt);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM automation_rule WHERE name = ? AND deleted_at IS NULL ORDER BY id DESC LIMIT 1", Long.class, name.trim());
        return get(id);
    }

    /**
     * 校验并规范化 schedule JSON(M4-01):频率与 triggerType 一致、时间/星期/时区有效。
     * 返回规范化的 JSON 字符串(供落库)。
     */
    private String normalizeSchedule(String scheduleJson, String type) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(scheduleJson);
            // 频率以 triggerType 为准(防前后端不一致)
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("frequency", type);
            ScheduleCalculator.Schedule parsed = ScheduleCalculator.parse(node);
            if (parsed == null) {
                throw new BusinessException(400,
                        "schedule 非法:需要 localTime(HH:mm)、weekly 还需 dayOfWeek(1-7)、timezone(IANA)");
            }
            return objectMapper.writeValueAsString(node);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "schedule 解析失败: " + e.getMessage());
        }
    }

    /** 由 schedule 计算下一次计划执行(规则时区;转本地挂钟存储)。 */
    private java.time.LocalDateTime computeNextRunAt(String scheduleJson) {
        try {
            ScheduleCalculator.Schedule parsed = ScheduleCalculator.parse(objectMapper.readTree(scheduleJson));
            if (parsed == null) {
                return null;
            }
            // 基准必须是规则时区墙钟(2026-09-21 补修):nextRunAt 存规则时区墙钟,
            // 传服务器墙钟会整体偏移一个时差——实测服务器上海/规则纽约时,
            // 创建时把上海 13:00 当纽约墙钟,首次执行被算到明天(跳过今天 09:00)。
            return ScheduleCalculator.nextAfter(parsed, ScheduleCalculator.nowIn(parsed));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 日程预览(M4-01):返回未来至少 3 次计划点(方案 §8.1「保存前预览」)。
     * 输入为未落库的 schedule JSON。
     */
    public List<String> previewSchedule(String scheduleJson, String triggerType, int count) {
        String type = triggerType == null ? "daily" : triggerType;
        String normalized = normalizeSchedule(scheduleJson, type);
        ScheduleCalculator.Schedule parsed;
        try {
            parsed = ScheduleCalculator.parse(objectMapper.readTree(normalized));
        } catch (Exception e) {
            throw new BusinessException(400, "schedule 解析失败: " + e.getMessage());
        }
        if (parsed == null) {
            throw new BusinessException(400, "schedule 非法");
        }
        int n = Math.min(Math.max(count, 1), 10);
        List<String> out = new java.util.ArrayList<>(n);
        // 基准同样是规则时区墙钟(2026-09-21 补修):预览时间必须与创建/扫描
        // 同一口径,否则跨时区时「预览显示的」与「实际执行的」不一致。
        java.time.LocalDateTime cursor = ScheduleCalculator.nowIn(parsed);
        for (int i = 0; i < n; i++) {
            cursor = ScheduleCalculator.nextAfter(parsed, cursor);
            out.add(cursor.toString());
        }
        return out;
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

    /**
     * 规则专属会话 id(2026-09-20,定时任务=往会话发消息):
     * 确定性生成({@code auto-rule-<id>})——同一规则每次触发都进同一个会话,
     * 历史可累积;会话由 agent-service 侧按需创建/复活。
     */
    static String sessionIdFor(long ruleId) {
        return "auto-rule-" + ruleId;
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
            // 定时任务=往会话发消息:agent 动作落进规则专属会话(标题「定时任务:规则名」),
            // 消息 sender=automation、AI 回答与步骤持久化;SQL 动作忽略会话参数。
            String sessionId = sessionIdFor(rule.id());
            String sessionTitle = "定时任务:" + rule.name();
            // 规则表记录会话 id(可观测;会话被删后由 agent 侧复活)
            try {
                jdbcTemplate.update(
                        "UPDATE automation_rule SET session_id = ? WHERE id = ? AND deleted_at IS NULL",
                        sessionId, rule.id());
            } catch (Exception e) {
                log.debug("session id persist failed for rule {}: {}", rule.id(), e.getMessage());
            }
            ActionExecutor.ActionResult actionResult = actionExecutor.execute(rule.actionJson(), sessionId, sessionTitle);
            String detail = actionResult.detail();
            long duration = System.currentTimeMillis() - start;
            // 终态直接来自执行器(F2,2026-09-26):不再按 detail 文本前缀推断——
            // partial(有成果但有未完成项)/ unknown(连接中断,结果未知)不再被
            // 当成成功发通知,cancelled 单独呈现。
            String status = switch (actionResult.status()) {
                case "completed" -> "success";
                case "partial" -> "partial";
                case "cancelled" -> "cancelled";
                case "unknown" -> "unknown";
                default -> "failed";
            };
            boolean ok = "success".equals(status);
            jdbcTemplate.update(
                    "INSERT INTO execution_record (rule_id, duration_ms, status, detail) VALUES (?, ?, ?, ?)",
                    rule.id(), duration, status, detail);
            jdbcTemplate.update(
                    "UPDATE automation_rule SET last_run_at = now(), status = ? WHERE id = ? AND deleted_at IS NULL",
                    ok ? "active" : "error", rule.id());
            // 通知事件(Kafka,2026-09-19):自动任务执行完成/失败——用户在任何页面
            // 都能从通知中心看到(此前只有页面级轮询,切页就丢)。发布失败静默,
            // 不影响执行记录落库。partial/cancelled/unknown 不发「成功」通知。
            if (notificationPublisher != null) {
                String detailText = switch (status) {
                    case "success" -> "执行成功";
                    case "partial" -> "部分完成(存在未完成项,详情见执行历史)";
                    case "cancelled" -> "已取消(未产生完整结果)";
                    case "unknown" -> "结果未知(连接中断,该轮可能仍在后台运行)";
                    default -> "执行失败";
                };
                notificationPublisher.publish(
                        ok ? "taskDone" : "taskFail",
                        ok ? "任务执行完成" : ("unknown".equals(status) ? "任务结果未知" : "任务执行失败"),
                        "自动任务「" + rule.name() + "」" + detailText
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

    /**
     * 计划扫描(M4-01/M4-04):按 nextRunAt 找到期规则并执行。
     *
     * <p>语义(方案 §7.2):
     * <ul>
     *   <li>now ∈ [nextRunAt, nextRunAt+10min] → 执行,并推进 nextRunAt 到下一个未来计划点;</li>
     *   <li>now 超过宽限(missed)→ **不自动补跑**:落一条 missed_schedule 执行记录,
     *       推进 nextRunAt,等待用户手动补跑(方案 §7.2);</li>
     *   <li>去重(M4-04):推进 nextRunAt 与执行记录领取在同一事务内完成——
     *       重复扫描不会对同一计划点执行两次;</li>
     *   <li>暂停规则不触发;恢复后从下一个未来计划点开始(不追赶)。</li>
     * </ul>
     */
    public int runDueScheduled() {
        int ran = 0;
        for (RuleView rule : list()) {
            if (!rule.enabled() || !List.of("daily", "weekly").contains(rule.triggerType())) {
                continue;
            }
            if (rule.nextRunAt() == null) {
                continue;
            }
            // 规则时区墙钟(2026-09-21 修复):nextRunAt 存的是规则时区墙钟,
            // 用服务器墙钟(LocalDateTime.now())比较会引入整时区偏移的错误——
            // 实测服务器上海/规则纽约时提前 60 分钟触发。
            ScheduleCalculator.Schedule parsed = parseScheduleOf(rule);
            if (parsed == null) {
                continue; // schedule 不可解析:无法比较/推进,跳过
            }
            java.time.LocalDateTime now = ScheduleCalculator.nowIn(parsed);
            java.time.LocalDateTime scheduledAt = rule.nextRunAt().toLocalDateTime();
            if (now.isBefore(scheduledAt)) {
                continue; // 未到点
            }
            // 领取计划点(原子):条件更新 nextRunAt(仅当仍等于本次读取值)——
            // 两个实例/重复扫描只有一个能领到(方案 §7.3 的 (ruleId, scheduledAt) 去重等效实现)
            java.time.LocalDateTime next = ScheduleCalculator.nextAfter(parsed, now);
            int claimed = jdbcTemplate.update(
                    "UPDATE automation_rule SET next_run_at = ? WHERE id = ? AND next_run_at = ? AND deleted_at IS NULL",
                    next == null ? null : java.sql.Timestamp.valueOf(next),
                    rule.id(), java.sql.Timestamp.valueOf(scheduledAt));
            if (claimed == 0) {
                continue; // 另一实例已领取该计划点
            }
            if (ScheduleCalculator.withinGrace(scheduledAt, now)) {
                execute(rule);
                ran++;
            } else {
                // 超过宽限:记录 missed_schedule,不冒充 cancelled/completed(方案 §7.2)
                try {
                    jdbcTemplate.update(
                            "INSERT INTO execution_record (rule_id, duration_ms, status, detail) VALUES (?, 0, 'missed_schedule', ?)",
                            rule.id(), "计划点 " + scheduledAt + " 已错过(超过 " + ScheduleCalculator.GRACE_MINUTES
                                    + " 分钟宽限),未自动补跑;可在任务页手动运行。");
                } catch (Exception e) {
                    log.warn("failed to record missed schedule for rule {}: {}", rule.id(), e.getMessage());
                }
            }
        }
        return ran;
    }

    /** 读取规则的 schedule 并解析(供调度推进 nextRunAt 用)。 */
    private ScheduleCalculator.Schedule parseScheduleOf(RuleView rule) {
        if (rule.schedule() == null || rule.schedule().isBlank()) {
            return null;
        }
        try {
            return ScheduleCalculator.parse(objectMapper.readTree(rule.schedule()));
        } catch (Exception e) {
            return null;
        }
    }

    private RuleView get(long id) {
        List<RuleView> rows = jdbcTemplate.query(
                "SELECT id, name, trigger_type, trigger_expr, action, enabled, status, last_run_at, schedule, next_run_at, configuration_status FROM automation_rule WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new RuleView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("trigger_type"),
                        rs.getString("trigger_expr"),
                        rs.getString("action"),
                        rs.getBoolean("enabled"),
                        rs.getString("status"),
                        rs.getTimestamp("last_run_at"),
                        rs.getString("schedule"),
                        rs.getTimestamp("next_run_at"),
                        rs.getString("configuration_status")),
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
            java.sql.Timestamp lastRunAt,
            /** 日程 JSON(M4-01;null = 手动/需配置)。 */
            String schedule,
            /** 下一次计划执行(派生;手动规则/需配置为 null)。 */
            java.sql.Timestamp nextRunAt,
            /** ok / needs_config(存量无时刻规则标需配置,方案 §8.3)。 */
            String configurationStatus) {
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
