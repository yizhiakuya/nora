package com.nora.notification.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 通知持久化:消费落库 + 前端查询/已读/清空。
 *
 * <p>事件在 Kafka 消费端(NotificationConsumer)转入这里;前端 API 从
 * 这里读。单用户量级,分页按 limit 截断即可(上限 200)。
 */
@Service
public class NotificationStore {

    private static final Logger log = LoggerFactory.getLogger(NotificationStore.class);

    private final JdbcTemplate jdbcTemplate;

    public NotificationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 落一条通知(消费端/前端上报调用)。
     *
     * @param event 事件类型(与前端偏好开关同名)
     * @param title 标题
     * @param detail 详情
     * @param source producer 服务名(frontend = 前端上报)
     * @return 新行 id
     */
    public long save(String event, String title, String detail, String source) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO notification (event, title, detail, source) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event == null || event.isBlank() ? "general" : event,
                title == null ? "" : title,
                detail,
                source);
        log.debug("notification saved: [{}] {} ({})", event, title, source);
        return id == null ? 0 : id;
    }

    /** 最近通知,最新在前。 */
    public List<NotificationView> list(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        return jdbcTemplate.query(
                "SELECT id, event, title, detail, source, read, created_at FROM notification "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new NotificationView(
                        rs.getLong("id"),
                        rs.getString("event"),
                        rs.getString("title"),
                        rs.getString("detail"),
                        rs.getString("source"),
                        rs.getBoolean("read"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)),
                bounded);
    }

    /** 未读数(铃铛角标)。 */
    public int unreadCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE read = FALSE", Integer.class);
        return n == null ? 0 : n;
    }

    /** 全部标记已读。 */
    public int markAllRead() {
        return jdbcTemplate.update("UPDATE notification SET read = TRUE WHERE read = FALSE");
    }

    /** 单条标记已读。 */
    public int markRead(long id) {
        return jdbcTemplate.update("UPDATE notification SET read = TRUE WHERE id = ? AND read = FALSE", id);
    }

    /** 清空全部。 */
    public int clearAll() {
        return jdbcTemplate.update("DELETE FROM notification");
    }

    /** 前端视图(字段与前端 AppNotification 对齐)。 */
    public record NotificationView(long id, String event, String title, String detail,
                                   String source, boolean read, java.time.LocalDateTime createdAt) {
    }
}
