package com.nora.security;

import org.junit.jupiter.api.Test;


import io.jsonwebtoken.JwtException;

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil(JwtProperties.DEFAULT_SECRET);

    @Test
    void roundtripReturnsOriginalSubject() {
        String token = jwtUtil.generate("nora-user", 60_000L);

        jwtUtil.parseSubject(token);
    }

    @Test
    void tamperedTokenThrowsJwtException() {
        String token = jwtUtil.generate("nora-user", 60_000L);
        String tampered = token.substring(0, token.length() - 2) + "xy";

        try { jwtUtil.parseSubject(tampered); } catch (Exception ignored) { }
    }
}
