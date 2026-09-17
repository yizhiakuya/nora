package com.nora.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 签发与校验的配置属性。
 *
 * <pre>{@code
 * nora:
 *   security:
 *     secret: nora-dev-secret-change-me-32bytes!!
 *     expiration-ms: 86400000
 * }</pre>
 *
 * @param secret       HMAC-SHA 签名密钥;至少 32 字符
 * @param expirationMs token 生命周期(毫秒)
 */
@ConfigurationProperties(prefix = "nora.security")
public record JwtProperties(String secret, long expirationMs) {

    /** 开发兜底密钥;33 字符,满足 32 字节 HMAC-SHA 下限。 */
    public static final String DEFAULT_SECRET = "nora-dev-secret-change-me-32bytes!!";

    /** 默认 token 生命周期:24 小时。 */
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
