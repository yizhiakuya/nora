package com.nora.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JWT issuance and validation.
 *
 * <pre>{@code
 * nora:
 *   security:
 *     secret: nora-dev-secret-change-me-32bytes!!
 *     expiration-ms: 86400000
 * }</pre>
 *
 * @param secret       HMAC-SHA signing secret; must be at least 32 characters
 * @param expirationMs token lifetime in milliseconds
 */
@ConfigurationProperties(prefix = "nora.security")
public record JwtProperties(String secret, long expirationMs) {

    /** Development fallback secret; 33 chars, satisfies the 32-byte HMAC-SHA minimum. */
    public static final String DEFAULT_SECRET = "nora-dev-secret-change-me-32bytes!!";

    /** Default token lifetime: 24 hours. */
    public static final long DEFAULT_EXPIRATION_MS = 86_400_000L;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            secret = DEFAULT_SECRET;
        }
        if (expirationMs <= 0) {
            expirationMs = DEFAULT_EXPIRATION_MS;
        }
    }

    @Override
    public String secret() {
        return secret;
    }

    @Override
    public long expirationMs() {
        return expirationMs;
    }
}
