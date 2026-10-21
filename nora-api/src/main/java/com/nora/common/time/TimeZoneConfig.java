package com.nora.common.time;

import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;

/**
 * 全局统一时区(nora 全家桶约定):
 * <ul>
 *   <li>JVM 默认时区 —— 影响日志时间戳、{@code NOW()} 经 JDBC 写入的挂钟时间、
 *       {@code new Date()}/{@code LocalDateTime.now()} 等一切隐式取时;</li>
 *   <li>Jackson 序列化时区 —— {@code Date}/{@code Instant} 输出的偏移量。</li>
 * </ul>
 * 值来自 {@code nora.timezone}(默认 Asia/Shanghai)。必须在 Bean 初始化
 * 最早期执行:DataSource/Jackson 等 Bean 装配时就要拿到正确时区,所以用
 * 静态块级联 + {@code @PostConstruct} 双保险,而不是依赖 Bean 顺序。
 *
 * <p>DB 侧约定:时间列统一 {@code timestamp without time zone},存本地
 * (nora.timezone)挂钟时间;读取用 {@code LocalDateTime} 原样进出,两端
 * 服务时区一致即无歧义。跨时区部署时只改这一个配置项。
 */
@org.springframework.context.annotation.Configuration
public class TimeZoneConfig {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneConfig.class);

    static {
        // 静态块兜底:类加载即生效,早于 DataSource/Jackson 等任何 Bean 装配。
        // 默认 Asia/Shanghai;@PostConstruct 里按 nora.timezone 配置覆盖。
        setDefault("Asia/Shanghai");
    }

    @Value("${nora.timezone:Asia/Shanghai}")
    private String timezone;

    @PostConstruct
    public void apply() {
        setDefault(timezone);
    }

    private static void setDefault(String timezone) {
        TimeZone tz = TimeZone.getTimeZone(timezone);
        TimeZone current = TimeZone.getDefault();
        if (tz.getRawOffset() != current.getRawOffset() || !timezone.equals(current.getID())) {
            TimeZone.setDefault(tz);
            log.info("global timezone set to {} (offset={}s)", timezone, tz.getRawOffset() / 1000);
        }
    }
}
