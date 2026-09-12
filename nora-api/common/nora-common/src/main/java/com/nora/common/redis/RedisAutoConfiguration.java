package com.nora.common.redis;

import io.lettuce.core.RedisClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-wiring for the shared Redis facade: any service depending on
 * nora-common gets a {@link NoraRedis} bean and can opt in with
 * {@code nora.redis.enabled: true}.
 *
 * <p>Disabled by default — Redis is an optimisation/coordination layer, never
 * a boot requirement. When disabled or unreachable, {@link NoraRedis} returns
 * empty results and callers use their in-process fallback.
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
