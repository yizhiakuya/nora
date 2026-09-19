package com.nora.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 登录端点(2026-09-19 令牌登录):网关自身提供,不经下游服务。
 *
 * <ul>
 *   <li>{@code GET /api/auth/status} → 是否需要登录(前端据此决定跳不跳登录页);
 *       已带有效令牌时回 {@code authenticated: true}</li>
 *   <li>{@code POST /api/auth/login} → 校验令牌({@code {"token": "..."}}),
 *       正确回 200(前端存 localStorage 后续请求携带),错误回 401</li>
 * </ul>
 *
 * <p>令牌校验逻辑复用 {@link AuthGatewayFilter}(常量时间比较)。
 * 注意:登录端点在 filter 白名单里——校验由本控制器自己做(它拿
 * 请求体里的 token 而不是 header)。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthGatewayFilter authFilter;
    private final String expectedToken;

    public AuthController(AuthGatewayFilter authFilter,
                          @org.springframework.beans.factory.annotation.Value("${nora.auth.token:}") String expectedToken) {
        this.authFilter = authFilter;
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    /** 登录状态:需要登录吗?当前请求带令牌了吗? */
    @GetMapping("/status")
    public Map<String, Object> status(ServerWebExchange exchange) {
        if (!authFilter.authRequired()) {
            return Map.of("authRequired", false, "authenticated", true);
        }
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        String candidate = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : exchange.getRequest().getQueryParams().getFirst("token");
        boolean ok = candidate != null && constantTimeEquals(candidate, expectedToken);
        return Map.of("authRequired", true, "authenticated", ok);
    }

    /** 令牌登录:校验通过回 ok(前端存令牌,后续请求携带)。 */
    @PostMapping("/login")
    public Mono<Void> login(@RequestBody Map<String, String> body, ServerHttpResponse response) {
        String candidate = body == null ? null : body.get("token");
        boolean ok = candidate != null && !candidate.isBlank()
                && constantTimeEquals(candidate.trim(), expectedToken);
        if (ok) {
            return write(response, HttpStatus.OK,
                    "{\"code\":0,\"data\":true,\"message\":\"ok\"}");
        }
        return write(response, HttpStatus.UNAUTHORIZED,
                "{\"code\":401,\"data\":null,\"message\":\"令牌不正确\","
                        + "\"category\":\"UNAUTHORIZED\",\"retryable\":false,"
                        + "\"hint\":\"请核对服务端 NORA_AUTH_TOKEN 配置\"}");
    }

    private Mono<Void> write(ServerHttpResponse response, HttpStatus status, String body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
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
}
