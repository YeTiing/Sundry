package com.sundry.auth.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class JwtService {

    private static final String BLACKLIST_KEY = "auth:jwt:blacklist:%s";

    private final JwtProperties properties;
    private final StringRedisTemplate redis;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public JwtService(JwtProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;

        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret 未配置");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.jwt.secret 必须是 Base64 编码", ex);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少需要 32 字节");
        }

        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
    }

    public IssuedAccessToken issueAccessToken(Long userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTtl());
        String jti = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(jti)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, jti, expiresAt);
    }

    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    public void blacklist(String jti, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(BLACKLIST_KEY.formatted(jti), "1", ttl.toSeconds(), TimeUnit.SECONDS);
    }

    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(BLACKLIST_KEY.formatted(jti)));
    }

    public record IssuedAccessToken(String token, String jti, Instant expiresAt) {
    }
}
