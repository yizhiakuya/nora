package com.nora.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JJWT 0.12 API 的薄封装,用于签发与读取签名 JWT。
 *
 * <p>紧凑 HS256 token,仅带单个 subject claim;其余 claim 留给调用方。
 * 无效或被篡改的 token 以 {@link JwtException} 抛出。
 */
public final class JwtUtil {

    /** HMAC-SHA 密钥至少需要 32 字节(256 位)源材料。 */
    private static final int MIN_SECRET_BYTES = 32;

    private static final char PADDING = ' ';

    private final SecretKey key;

    /**
     * @param secret HMAC-SHA 签名密钥;过短的密钥右侧补齐到 32 字节下限,
     *               null/空白回退开发密钥
     */
    public JwtUtil(String secret) {
        this.key = Keys.hmacShaKeyFor(normalize(secret).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为给定 subject 签发紧凑 JWT,在给定时长后过期。
     *
     * @param subject      已认证主体名
     * @param expirationMs token 生命周期(毫秒)
     * @return 已签名紧凑 JWT
     */
    public String generate(String subject, long expirationMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名并返回内嵌的 subject。
     *
     * @param token 已签名紧凑 JWT
     * @return subject claim
     * @throws JwtException token 畸形、被篡改或已过期时
     */
    public String parseSubject(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /** 补齐或默认化密钥,使其始终满足 {@link #MIN_SECRET_BYTES}。 */
    private static String normalize(String secret) {
        String value = (secret == null || secret.isBlank()) ? JwtProperties.DEFAULT_SECRET : secret;
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            value = value + Character.toString(PADDING).repeat(MIN_SECRET_BYTES);
        }
        return value;
    }
}
