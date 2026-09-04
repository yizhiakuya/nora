package com.nora.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Thin wrapper over the JJWT 0.12 API for issuing and reading signed JWTs.
 *
 * <p>Compact HS256 tokens with a single subject claim; all other claims are
 * left to callers. Invalid or tampered tokens surface as {@link JwtException}.
 */
public final class JwtUtil {

    /** HMAC-SHA keys require at least 32 bytes (256 bits) of source material. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final char PADDING = ' ';

    private final SecretKey key;

    /**
     * @param secret HMAC-SHA signing secret; shorter secrets are right-padded
     *               to the 32-byte minimum, null/blank falls back to the dev secret
     */
    public JwtUtil(String secret) {
        this.key = Keys.hmacShaKeyFor(normalize(secret).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Signs a compact JWT for the given subject, expiring after the given lifetime.
     *
     * @param subject      authenticated principal name
     * @param expirationMs token lifetime in milliseconds
     * @return signed compact JWT
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
     * Verifies the signature and returns the embedded subject.
     *
     * @param token signed compact JWT
     * @return the subject claim
     * @throws JwtException if the token is malformed, tampered, or expired
     */
    public String parseSubject(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /** Pads or defaults the secret so it always satisfies {@link #MIN_SECRET_BYTES}. */
    private static String normalize(String secret) {
        String value = (secret == null || secret.isBlank()) ? JwtProperties.DEFAULT_SECRET : secret;
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            value = value + Character.toString(PADDING).repeat(MIN_SECRET_BYTES);
        }
        return value;
    }
}
