package com.nora.automation.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 日程计算(M4-01,2026-09-20,方案 §7.2)。
 *
 * <p>契约(schedule JSONB):
 * <pre>
 * { "frequency": "daily"|"weekly", "localTime": "HH:mm",
 *   "dayOfWeek": 1-7 (weekly;1=周一), "timezone": "Asia/Shanghai" }
 * </pre>
 *
 * <p>规则:
 * <ul>
 *   <li>调度基于**下一次绝对时间** nextRunAt(存本地挂钟,显示转换到规则时区);</li>
 *   <li>新建规则默认第一次在未来计划点执行;</li>
 *   <li>每周按星期计算,不用"距上次大约六/七天"替代;</li>
 *   <li>DST 不存在的本地时刻顺延到当天第一个有效时刻;重复时刻仅取第一次
 *       (用 Java 时间 API 的时区规则,不自己维护偏移表);</li>
 *   <li>迟到宽限 10 分钟:now ∈ [scheduledAt, scheduledAt+10min] 视为准时执行;
 *       超过宽限记 missed(方案 §7.2)。</li>
 * </ul>
 */
public final class ScheduleCalculator {

    /** 迟到宽限:计划点后 10 分钟内仍执行(方案 §7.2)。 */
    public static final int GRACE_MINUTES = 10;

    private ScheduleCalculator() {
    }

    /** 解析后的日程。 */
    public record Schedule(String frequency, LocalTime localTime, Integer dayOfWeek, ZoneId zone) {
    }

    /**
     * 从 schedule JSONB 解析;非法/缺字段返回 null(调用方标 needs_config)。
     */
    public static Schedule parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String frequency = node.path("frequency").asText("");
        if (!"daily".equals(frequency) && !"weekly".equals(frequency)) {
            return null;
        }
        String timeText = node.path("localTime").asText("");
        LocalTime time;
        try {
            time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception e) {
            try {
                time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception e2) {
                return null;
            }
        }
        Integer dayOfWeek = null;
        if ("weekly".equals(frequency)) {
            int dow = node.path("dayOfWeek").asInt(0);
            if (dow < 1 || dow > 7) {
                return null;
            }
            dayOfWeek = dow;
        }
        String tz = node.path("timezone").asText("");
        ZoneId zone;
        try {
            zone = tz.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(tz);
        } catch (Exception e) {
            return null;
        }
        return new Schedule(frequency, time, dayOfWeek, zone);
    }

    /**
     * 规则时区下的当前墙钟——比较与推进的统一基准。
     *
     * <p>为什么必须用它而不是 {@code LocalDateTime.now()}(2026-09-21 修复):
     * 服务器时区 ≠ 规则时区时,把服务器墙钟当作规则墙钟会引入整时区偏移的错误——
     * 实测(服务器上海、规则纽约每日 09:00):扫描把上海 20:00 与纽约 09:00
     * 直接比较,提前 60 分钟触发;nextAfter 则把上海 13:00 当纽约墙钟,
     * 跳过一整天。nextRunAt 存的是规则时区墙钟,所有比较/推进都必须在
     * 规则时区墙钟空间里做。
     */
    public static LocalDateTime nowIn(Schedule schedule) {
        return ZonedDateTime.now(schedule.zone()).toLocalDateTime();
    }

    /**
     * 计算 {@code after} 之后的第一个计划点(规则时区,转本地挂钟返回)。
     *
     * <p>{@code after} 必须是**规则时区的墙钟**(用 {@link #nowIn} 取当前时刻,
     * 或用上一次返回值的递增);传服务器墙钟会得到偏移一整个时差的错误结果。
     *
     * <p>DST 处理:目标本地时刻不存在时(春季跳变),顺延到当天第一个有效
     * 时刻(Java 的 {@code ZonedDateTime.of} 会自动前推);重复时刻取第一次。
     */
    public static LocalDateTime nextAfter(Schedule schedule, LocalDateTime after) {
        ZoneId zone = schedule.zone();
        ZonedDateTime cursor = after.atZone(zone);
        // 从"候选点 > after"开始:先看当天(或本周)候选,再逐日推进
        for (int i = 0; i < 8; i++) {
            LocalDate day = cursor.toLocalDate().plusDays(i);
            if ("weekly".equals(schedule.frequency())) {
                DayOfWeek target = DayOfWeek.of(schedule.dayOfWeek());
                if (day.getDayOfWeek() != target) {
                    continue;
                }
            }
            ZonedDateTime candidate = ZonedDateTime.of(day, schedule.localTime(), zone);
            // DST 不存在的时刻:Java 会前推到有效时刻(如 02:30 → 03:00);取该结果
            if (candidate.toLocalDateTime().isAfter(after)) {
                return candidate.toLocalDateTime();
            }
        }
        // 理论不可达(daily 8 天内必命中;weekly 8 天覆盖整周)
        return after.plusDays(1).with(schedule.localTime());
    }

    /**
     * 该计划点是否在迟到宽限内可执行:now ∈ [scheduledAt, scheduledAt+grace]。
     */
    public static boolean withinGrace(LocalDateTime scheduledAt, LocalDateTime now) {
        if (scheduledAt == null || now == null) {
            return false;
        }
        return !now.isBefore(scheduledAt) && !now.isAfter(scheduledAt.plusMinutes(GRACE_MINUTES));
    }
}
