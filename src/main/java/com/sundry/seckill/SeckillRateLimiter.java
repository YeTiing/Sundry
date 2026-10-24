package com.sundry.seckill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 每用户每秒固定窗口限流。Redis 异常时放行，避免限流组件拖垮抢购。
 */
@Service
public class SeckillRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SeckillRateLimiter.class);
    private static final String KEY = "rate:seckill:%d:%d";

    private static final DefaultRedisScript<Long> LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SeckillProperties properties;

    public SeckillRateLimiter(StringRedisTemplate redis, SeckillProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean tryAcquire(Long userId) {
        int window = Math.max(1, properties.getRateLimitWindowSeconds());
        long windowIndex = Instant.now().getEpochSecond() / window;
        String key = KEY.formatted(userId, windowIndex);
        try {
            Long allowed = redis.execute(LIMIT_SCRIPT, List.of(key),
                    String.valueOf(properties.getRateLimitPerSecond()),
                    String.valueOf(window * 2));
            return allowed != null && allowed == 1L;
        } catch (RuntimeException ex) {
            log.warn("秒杀限流失败，按放行处理 userId={}", userId, ex);
            return true;
        }
    }
}
