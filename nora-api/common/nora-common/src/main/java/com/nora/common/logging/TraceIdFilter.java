package com.nora.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入口链路过滤器(全部 MVC 服务自动生效,经组件扫描注册):
 * <ol>
 *   <li>从 {@code X-Nora-Trace-Id} header 读取上游 traceId(网关/服务间调用),
 *       没有则生成新 ID —— 前端直连或外部探测也能全程追踪;</li>
 *   <li>写入 MDC,本请求所有日志自动携带;</li>
 *   <li>响应头回写 traceId,前端可从错误响应里取到并展示/上报;</li>
 *   <li>请求结束打一行访问日志(方法/路径/状态/耗时),JSON 文件里就是现成的
 *       请求时间线,不再依赖 Tomcat access log。</li>
 * </ol>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    /** SSE 流式响应与文件下载耗时无意义且拉长访问日志,只记开始 */
    private static final String STREAM_MARKER = TraceIdFilter.class.getName() + ".STREAM";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceContext.TRACE_HEADER);
        String traceId = incoming != null && !incoming.isBlank() && incoming.length() <= 64
                ? incoming
                : TraceContext.newTraceId();
        TraceContext.setTraceId(traceId);
        response.setHeader(TraceContext.TRACE_HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            // SSE/长连接流:emitter 在异步线程上 complete,此处拿不到真实结束时刻
            boolean streaming = request.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST
                    || request.getAttribute(STREAM_MARKER) != null;
            String accept = request.getHeader("Accept");
            boolean sse = accept != null && accept.contains("text/event-stream");
            if (streaming || sse) {
                log.info(">> {} {} [sse]", request.getMethod(), request.getRequestURI());
            } else {
                log.info("{} {} -> {} ({}ms)",
                        request.getMethod(), request.getRequestURI(), response.getStatus(),
                        System.currentTimeMillis() - start);
            }
            TraceContext.clear();
        }
    }
}
