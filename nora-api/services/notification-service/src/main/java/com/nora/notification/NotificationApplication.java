package com.nora.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * 通知服务(2026-09-19):Kafka 事件总线的消费端 + 通知中心持久化。
 *
 * <p>数据流:业务服务(automation/env/rag)作为 producer 发 {@link com.nora.common.notification.NotificationEvent}
 * 到 {@code nora.notifications} topic → 本服务消费落库 schema_notification →
 * 前端从 REST API 拉取展示(已读状态也在服务端)。
 *
 * <p>为什么独立服务:通知是跨业务基础设施(谁产生谁推送),消费方只面对
 * 一个 API;业务服务与通知存储解耦(producer 不碰 notification 表)。
 * Kafka 挂了只丢通知,不影响任何主业务(producer 侧静默降级)。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.nora")
@EnableKafka
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
