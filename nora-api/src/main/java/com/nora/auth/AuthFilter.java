package com.nora.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    private final byte[] expectedToken;

    public AuthFilter(@Value("${nora.auth.token:}") String token) {
        expectedToken = (token == null ? "" : token.trim()).getBytes(StandardCharsets.UTF_8);
    }

    public boolean authRequired() { return expectedToken.length > 0; }

    public boolean matches(String token) {
        return token != null && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }

    public boolean authenticated(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String candidate = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : request.getParameter("token");
        return !authRequired() || matches(candidate);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getServletPath();
        if (path.startsWith("/api/auth/") || path.equals("/api/chat/health")
                || path.startsWith("/actuator/") || "OPTIONS".equals(request.getMethod()) || authenticated(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"data\":null,\"message\":\"未登录或令牌无效\","
                + "\"category\":\"UNAUTHORIZED\",\"retryable\":false,\"hint\":\"请在登录页输入访问令牌(NORA_AUTH_TOKEN)\"}");
    }
}
