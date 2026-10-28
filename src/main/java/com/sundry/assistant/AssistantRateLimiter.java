package com.sundry.assistant;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 跨实例共享的滑动窗口限流：Redis ZSET 记录最近 60 秒的请求。
 * 多实例部署时同一用户的配额仍然只有一份。
 */
@Component
public class AssistantRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AssistantRateLimiter.class);
    private static final String KEY = "assistant:rate:%d";
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration KEY_TTL = Duration.ofMinutes(2);

    /**
     * 清理窗口外的记录，未超限则写入本次请求。
     * 返回 0 表示放行，1 表示超限。
     */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local cutoff = now - tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
            local count = redis.call('ZCARD', KEYS[1])
            if count >= tonumber(ARGV[3]) then
                return 1
            end
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5]))
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int limit;

    public AssistantRateLimiter(StringRedisTemplate redis,
                                @Value("${app.assistant.rate-limit-per-minute:10}") int limit) {
        this.redis = redis;
        this.limit = Math.max(1, limit);
    }

    public void check(Long userId) {
        long now = System.currentTimeMillis();
        String key = String.format(KEY, userId);
        Long result;
        try {
            result = redis.execute(ACQUIRE, List.of(key),
                    String.valueOf(now),
                    String.valueOf(WINDOW.toMillis()),
                    String.valueOf(limit),
                    now + ":" + UUID.randomUUID(),
                    String.valueOf(KEY_TTL.toMillis()));
        } catch (RuntimeException ex) {
            // 限流依赖 Redis：不可用时放行，避免整条助手链路不可用
            log.warn("助手限流检查失败，放行本次请求 userId={}", userId, ex);
            return;
        }
        if (result != null && result == 1L) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }
}
