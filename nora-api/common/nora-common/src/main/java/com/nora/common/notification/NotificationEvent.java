package com.nora.common.notification;

/**
 * 通知事件(跨服务 Kafka 消息契约,2026-09-19)。
 *
 * <p>Producer = 业务服务(automation 任务完成 / env 进程守护 / rag 索引完成);
 * Consumer = notification-service(落库,前端轮询展示)。
 *
 * <p>字段刻意最小化:接收方展示所需的一切都在这里,不引入跨服务的数据库耦合。
 *
 * @param event 事件类型(taskDone/taskFail/indexed/svcError/general——与前端通知偏好开关同名)
 * @param title 通知标题(一句话)
 * @param detail 详情(可空)
 * @param source 来源服务名(automation-service/env-service/rag-service…)
 * @param at   事件时间(ISO-8601,消费方原样存)
 */
public record NotificationEvent(String event, String title, String detail, String source, String at) {

    /** 便捷构造:以属性里的来源名与当前时间补全(JVM 默认时区由 TimeZoneConfig 统一为 Asia/Shanghai)。 */
    public static NotificationEvent of(NotificationProperties props, String event, String title, String detail) {
        return new NotificationEvent(event, title, detail, props.source(),
                java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault()).toString());
    }
}
