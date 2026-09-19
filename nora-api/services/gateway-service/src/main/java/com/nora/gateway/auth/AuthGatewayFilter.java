package com.nora.gateway.auth;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * 令牌登录鉴权(2026-09-19):单用户自部署工作台的访问门。
 *
 * <p>为什么是"令牌"而非完整用户系统:本工作台是单用户自部署——不需要
 * 注册/多用户/权限体系,只需要一道门防止公网暴露或内网误访问。服务端配
 * 一个访问令牌({@code NORA_AUTH_TOKEN}),登录页输入令牌换访问权。
 *
 * <p>校验位置:网关(所有 API 的单一入口)——一处生效覆盖全部服务,下游
 * 服务无需改动。请求需带 {@code Authorization: Bearer <token>} 或
 * {@code ?token=<token>}(SSE/EventSource 无法自定义 header,需 query 通道)。
 *
 * <p>未配置令牌({@code NORA_AUTH_TOKEN} 为空)= 免登录——本地开发/纯内网
 * 场景零配置可用;一旦配置即全 API 生效(白名单除外:auth 端点自身与健康
 * 检查)。
 */
@Component
public class AuthGatewayFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGatewayFilter.class);

    /** 令牌配置(环境变量 NORA_AUTH_TOKEN 或 nora.auth.token);空=免登录。 */
    private final String expectedToken;

    public AuthGatewayFilter(@Value("${nora.auth.token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
        if (!this.expectedToken.isEmpty()) {
            log.info("auth: token login ENABLED ({} chars)", this.expectedToken.length());
        } else {
            log.info("auth: token login disabled (NORA_AUTH_TOKEN not set) — API is open");
        }
    }

    /** 是否启用了鉴权(供 /api/auth/status 告知前端是否需要登录页)。 */
    public boolean authRequired() {
        return !expectedToken.isEmpty();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!authRequired()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        // 白名单:登录端点自身、健康检查、CORS 预检
        if (path.startsWith("/api/auth/")
                || path.equals("/api/chat/health")
                || "OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod().name())) {
            return chain.filter(exchange);
        }
        if (tokenMatches(exchange)) {
            return chain.filter(exchange);
        }
        return unauthorized(exchange);
    }

    /** 校验 Bearer header 或 ?token= query(常量时间比较,防时序侧信道)。 */
    private boolean tokenMatches(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        String candidate = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            candidate = header.substring(7).trim();
        }
        if (candidate == null) {
            candidate = exchange.getRequest().getQueryParams().getFirst("token");
        }
        return candidate != null && constantTimeEquals(candidate, expectedToken);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    /** 401 + Nora 信封(前端 client.ts 按 code/category 识别并跳登录页)。 */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"data\":null,\"message\":\"未登录或令牌无效\","
                + "\"category\":\"UNAUTHORIZED\",\"retryable\":false,"
                + "\"hint\":\"请在登录页输入访问令牌(NORA_AUTH_TOKEN)\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 在链路追踪(网关追踪 filter 为 HIGHEST_PRECEDENCE)之后执行
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
