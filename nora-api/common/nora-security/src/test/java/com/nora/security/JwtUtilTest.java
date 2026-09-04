package com.nora.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.JwtException;

class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil(JwtProperties.DEFAULT_SECRET);

    @Test
    void roundtripReturnsOriginalSubject() {
        String token = jwtUtil.generate("nora-user", 60_000L);

        assertEquals("nora-user", jwtUtil.parseSubject(token));
    }

    @Test
    void tamperedTokenThrowsJwtException() {
        String token = jwtUtil.generate("nora-user", 60_000L);
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThrows(JwtException.class, () -> jwtUtil.parseSubject(tampered));
    }
}
