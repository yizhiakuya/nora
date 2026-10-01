package com.nora.common.notification;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 通知发布门面的自动装配:任何依赖 nora-common 且带 kafka-clients 的服务
 * 都拿到 {@link NotificationPublisher} bean,经 {@code nora.notification.enabled: true}
 * 启用(默认禁用——通知是增益,绝不是启动要求)。
 *
 * <p>与 {@link com.nora.common.redis.RedisAutoConfiguration} 同款语义:
 * 未启用/不可达时静默降级,业务不受影响。
 */
@AutoConfiguration
@ConditionalOnClass(KafkaProducer.class)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NotificationPublisher notificationPublisher(NotificationProperties properties) {
        return new NotificationPublisher(properties);
    }
}
