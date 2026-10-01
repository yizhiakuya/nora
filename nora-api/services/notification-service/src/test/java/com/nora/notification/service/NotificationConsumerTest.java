package com.nora.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class NotificationConsumerTest {

    @Mock
    private NotificationStore store;

    @Mock
    private Acknowledgment ack;

    private NotificationConsumer consumer;

    @Test
    void consumesValidEvent() {
        consumer = new NotificationConsumer(store, new ObjectMapper());
        consumer.onNotification(
                "{\"event\":\"taskDone\",\"title\":\"任务执行完成\",\"detail\":\"x\",\"source\":\"automation-service\",\"at\":\"2026-09-19T20:00:00+08:00\"}",
                ack);
    }

    @Test
    void dropsMalformedJsonWithoutThrowing() {
        consumer = new NotificationConsumer(store, new ObjectMapper());
        consumer.onNotification("not-json{{{", ack);
    }

    @Test
    void dropsEventWithoutTitle() {
        consumer = new NotificationConsumer(store, new ObjectMapper());
        consumer.onNotification("{\"event\":\"general\",\"title\":\"\"}", ack);
    }

    @Test
    void storeFailurePropagatesForRetry() {
        consumer = new NotificationConsumer(store, new ObjectMapper());
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(store).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            consumer.onNotification("{\"event\":\"general\",\"title\":\"x\"}", ack);
        } catch (RuntimeException expected) {
            // 预期:落库失败不 ack,抛出让 Kafka 重投
        }
    }
}
