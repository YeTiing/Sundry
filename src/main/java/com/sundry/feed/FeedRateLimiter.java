package com.sundry.feed;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 读接口限流：登录用户按 userId，匿名按 IP；单实例固定 1 分钟窗口。
 */
@Component
public class FeedRateLimiter {

    private final Cache<String, AtomicInteger> counters;
    private final int userLimit;
    private final int ipLimit;

    public FeedRateLimiter(FeedProperties properties) {
        this.userLimit = Math.max(1, properties.getRateLimitUserPerMinute());
        this.ipLimit = Math.max(1, properties.getRateLimitIpPerMinute());
        this.counters = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();
    }

    public void check(Long userId, String ip) {
        String key = userId == null ? "ip:" + (ip == null ? "unknown" : ip) : "u:" + userId;
        int limit = userId == null ? ipLimit : userLimit;
        AtomicInteger counter = counters.get(key, ignored -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "feed rate limited");
        }
    }
}
