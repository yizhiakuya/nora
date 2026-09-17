package com.nora.common.logging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

/**
 * 日志/链路基建自动装配:依赖 nora-common 的 MVC 服务零配置获得——
 * <ul>
 *   <li>{@link TraceIdFilter}(最高优先级,先于一切业务过滤器写 MDC);</li>
 *   <li>{@code RestClient.Builder} 定制:出口自动带 traceId + 耗时日志,
 *       已有的 {@code RestClient.builder()} Bean 无需改动即可传播
 *       (Spring Boot 自动配置的 prototype Builder 全局生效)。</li>
 * </ul>
 * gateway 是 WebFlux、无 servlet 栈,自带 WebFlux 版过滤器,不走这里。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoggingAutoConfiguration {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public RestClient.Builder traceAwareRestClientBuilder() {
        return RestClient.builder().requestInterceptor(new TracePropagationInterceptor());
    }
}
