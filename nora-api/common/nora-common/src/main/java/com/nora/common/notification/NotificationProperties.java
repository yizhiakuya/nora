package com.nora.common.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知发布的配置属性(2026-09-19 Kafka 事件总线)。
 *
 * <pre>{@code
 * nora:
 *   notification:
 *     enabled: true
 *     bootstrap-servers: localhost:29092
 *     topic: nora.notifications
 * }</pre>
 *
 * <p>默认禁用——通知是增益,绝不是启动要求。未启用的服务不建 Kafka 连接,
 * {@link NotificationPublisher} 直接空操作。
 *
 * @param enabled          是否启用(默认 false)
 * @param bootstrapServers Kafka 地址(默认 localhost:29092)
 * @param topic            事件 topic(默认 nora.notifications)
 * @param source           事件来源名(各服务设为自己的服务名,用于通知归属)
 */
@ConfigurationProperties(prefix = "nora.notification")
public record NotificationProperties(Boolean enabled, String bootstrapServers, String topic, String source) {

    public NotificationProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            bootstrapServers = "localhost:29092";
        }
        if (topic == null || topic.isBlank()) {
            topic = "nora.notifications";
        }
        if (source == null || source.isBlank()) {
            source = "unknown";
        }
    }
}
