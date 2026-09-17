package com.nora.common.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.lettuce.core.RedisClient;

/**
 * 共享 Redis 门面的自动装配:任何依赖 nora-common 的服务都拿到
 * {@link NoraRedis} bean,可用 {@code nora.redis.enabled: true} 启用。
 *
 * <p>默认禁用——Redis 是优化/协调层,绝不是启动要求。禁用或不可达时,
 * {@link NoraRedis} 返回空结果,调用方使用其进程内兜底。
 */
@AutoConfiguration
@ConditionalOnClass(RedisClient.class)
@EnableConfigurationProperties(RedisProperties.class)
public class RedisAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NoraRedis noraRedis(RedisProperties properties) {
        return new NoraRedis(properties);
    }
}
