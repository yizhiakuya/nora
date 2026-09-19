package com.nora.common.notification;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通知事件发布门面(2026-09-19 Kafka 事件总线)。
 *
 * <p>设计对齐 {@link com.nora.common.redis.NoraRedis}:懒连接、失败静默、
 * <b>绝不阻断主业务</b>——通知是增益,不是依赖。Kafka 不可达时 send 打一行
 * 日志即返回,业务动作(任务执行/进程自愈/索引完成)完全不受影响。
 *
 * <p>用原生 {@link KafkaProducer} 而非 spring-kafka 的 KafkaTemplate:
 * 发布侧只需要"发一条 JSON 到固定 topic",引入完整 spring-kafka 会给每个
 * 业务服务(producer)都装上 consumer 基础设施(监听容器/反序列化配置),
 * 得不偿失。消费侧(notification-service)才用 spring-kafka。
 */
public class NotificationPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final NotificationProperties properties;
    private volatile KafkaProducer<String, String> producer;
    private volatile boolean loggedUnavailable = false;

    public NotificationPublisher(NotificationProperties properties) {
        this.properties = properties;
    }

    /** 功能开关是否打开(不探测连通性)。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.enabled());
    }

    /**
     * 发布一条通知事件(fire-and-forget,永不抛异常)。
     *
     * @param event 事件类型(与前端通知偏好开关同名:taskDone/taskFail/indexed/svcError/general)
     * @param title 标题
     * @param detail 详情(可空)
     */
    public void publish(String event, String title, String detail) {
        if (!enabled()) {
            return;
        }
        try {
            NotificationEvent payload = NotificationEvent.of(properties, event, title, detail);
            String json = toJson(payload);
            producer().send(new ProducerRecord<>(properties.topic(), event, json),
                    (meta, err) -> {
                        if (err != null) {
                            log.debug("notification publish failed ({}): {}", event, err.getMessage());
                        }
                    });
        } catch (Exception e) {
            noteUnavailable(e);
        }
    }

    /** 极简 JSON 序列化:字段固定,手写避免依赖注入(ObjectMapper 在 common 里可用但没必要)。 */
    private static String toJson(NotificationEvent e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendField(sb, "event", e.event());
        sb.append(',');
        appendField(sb, "title", e.title());
        sb.append(',');
        appendField(sb, "detail", e.detail());
        sb.append(',');
        appendField(sb, "source", e.source());
        sb.append(',');
        appendField(sb, "at", e.at());
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":").append(value == null ? "null" : quote(value));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 懒建 producer;失败静默降级(记一次日志,不抛)。 */
    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> p = producer;
        if (p != null) {
            return p;
        }
        synchronized (this) {
            if (producer == null) {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                // 通知是尽力而为:短超时快速失败,不拖住调用线程(默认 60s 太长)
                props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
                props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
                props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 8000);
                props.put(ProducerConfig.ACKS_CONFIG, "1");
                producer = new KafkaProducer<>(props);
                loggedUnavailable = false;
            }
            return producer;
        }
    }

    private void noteUnavailable(Exception e) {
        if (!loggedUnavailable) {
            loggedUnavailable = true;
            log.warn("kafka unavailable at {} ({}), notifications will be dropped silently",
                    properties.bootstrapServers(), e.getMessage());
        }
    }

    @Override
    public void close() {
        KafkaProducer<String, String> p = producer;
        if (p != null) {
            try {
                p.close(java.time.Duration.ofSeconds(3));
            } catch (Exception ignored) {
                // 尽力关闭
            }
        }
    }
}
