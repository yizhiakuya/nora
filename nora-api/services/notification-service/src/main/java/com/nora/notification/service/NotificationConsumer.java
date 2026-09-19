package com.nora.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kafka 消费端(2026-09-19 通知事件总线)。
 *
 * <p>订阅 {@code nora.notifications},把业务服务发来的事件落库。手动 ack:
 * 落库成功才提交 offset——处理失败不 ack,Kafka 会按配置重投(至少一次;
 * 单用户量级重复通知可接受,换取"不丢"的保证)。
 *
 * <p>坏消息(JSON 解析失败)直接 ack 丢弃:它是 producer 的 bug,重投
 * 一万次也解析不了,只会卡住分区。
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationStore store;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(NotificationStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${nora.notification.topic:nora.notifications}",
            groupId = "notification-service",
            properties = {
                    "auto.offset.reset=earliest",
                    "enable.auto.commit=false",
                    "max.poll.records=50"
            })
    public void onNotification(String message, Acknowledgment ack) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String event = node.path("event").asText("general");
            String title = node.path("title").asText("");
            String detail = node.path("detail").asText(null);
            String source = node.path("source").asText(null);
            if (title.isBlank()) {
                log.warn("notification event without title dropped: {}", message);
                ack.acknowledge();
                return;
            }
            store.save(event, title, detail, source);
            ack.acknowledge();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 坏消息直接丢弃(重投无意义);其余异常不 ack → Kafka 重投
            log.warn("malformed notification dropped: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("notification consume failed, will retry: {}", e.getMessage());
            throw e;
        }
    }
}
