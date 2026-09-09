package com.nora.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 出口链路拦截器(RestClient 统一挂载):
 * <ol>
 *   <li>把当前 MDC 的 traceId 写入 {@code X-Nora-Trace-Id} header,下游服务的
 *       TraceIdFilter 接住后,整条调用链日志同 ID 关联;</li>
 *   <li>调用结束打一行耗时日志(目标/状态/毫秒),服务间慢调用一眼可见。</li>
 * </ol>
 */
public class TracePropagationInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TracePropagationInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = TraceContext.traceId();
        if (traceId != null) {
            request.getHeaders().set(TraceContext.TRACE_HEADER, traceId);
        }
        long start = System.currentTimeMillis();
        String target = request.getURI().getHost() + ":" + request.getURI().getPort() + request.getURI().getPath();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.debug("-> {} {} {} ({}ms)", request.getMethod(), target, response.getStatusCode().value(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (IOException e) {
            log.warn("-> {} {} FAILED ({}ms): {}", request.getMethod(), target,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }
}
