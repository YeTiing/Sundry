package com.sundry.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final JwtProperties properties;

    public RefreshTokenService(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(KEY_PREFIX + hash(token), String.valueOf(userId), properties.getRefreshTtl());
        return token;
    }

    /**
     * 取出并立即作废，保证 refresh token 只能使用一次。
     */
    public Long consume(String token) {
        String value = redis.opsForValue().getAndDelete(KEY_PREFIX + hash(token));
        return value == null ? null : Long.valueOf(value);
    }

    public void revoke(String token) {
        redis.delete(KEY_PREFIX + hash(token));
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
