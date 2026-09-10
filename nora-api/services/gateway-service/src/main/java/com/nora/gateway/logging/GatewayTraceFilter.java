package com.nora.gateway.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 网关链路追踪(WebFlux 版,与 nora-common 的 TraceIdFilter 同语义):
 * <ol>
 *   <li>入口取 {@code X-Nora-Trace-Id}(前端已带则透传),没有则生成;</li>
 *   <li>响应头回写 traceId(前端可从任何响应取到);</li>
 *   <li>下游服务请求自动携带该 header —— 全链路(gateway→service→service)同 ID;</li>
 *   <li>路由完成打一行耗时日志;MDC 经 Reactor context 不可直接用,这里在
 *       日志调用处显式携带(见 TraceAwareLogger),避免跨线程串号。</li>
 * </ol>
 * gateway 不依赖 nora-common(servlet 语义不适用),键约定保持一致。
 */
@Component
public class GatewayTraceFilter implements WebFilter, GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayTraceFilter.class);

    public static final String TRACE_HEADER = "X-Nora-Trace-Id";
    private static final String TRACE_ATTR = GatewayTraceFilter.class.getName() + ".TRACE";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        String traceId = incoming != null && !incoming.isBlank() && incoming.length() <= 64
                ? incoming
                : newTraceId();
        exchange.getAttributes().put(TRACE_ATTR, traceId);
        // 响应头必须在提交前写入:提交后 Header 变为只读,在 doFinally 里 set 会抛
        // UnsupportedOperationException(默认 onErrorDropped,且挡住其后语句致路由日志缺失)。
        // 用 beforeCommit 而非直接 set:提交前上游响应头已合并进来,
        // 此时已带 trace 头(MVC 服务的 TraceIdFilter 会回写)则不重复叠加,否则由网关补齐。
        exchange.getResponse().beforeCommit(() -> {
            if (!exchange.getResponse().getHeaders().containsKey(TRACE_HEADER)) {
                exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /** GlobalFilter:在实际路由到下游前注入 trace header(改写请求必须在此阶段)。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getAttribute(TRACE_ATTR);
        ServerHttpRequest mutated = traceId == null ? exchange.getRequest()
                : exchange.getRequest().mutate().headers(h -> h.set(TRACE_HEADER, traceId)).build();
        long start = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> {
                    ServerHttpResponse response = exchange.getResponse();
                    String uri = exchange.getRequest().getURI().getPath();
                    String status = response.getStatusCode() != null ? String.valueOf(response.getStatusCode().value()) : "?";
                    log.info("{} {} -> {} [trace={}] ({}ms) [{}]",
                            exchange.getRequest().getMethod(), uri, status, traceId,
                            System.currentTimeMillis() - start, signal);
                });
    }

    /** 供前端错误上报按 traceId 关联:任意响应都可读到此 header。 */
    static String traceOf(ServerWebExchange exchange) {
        return exchange.getAttribute(TRACE_ATTR);
    }

    private static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
