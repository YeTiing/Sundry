package com.sundry.nl2sql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class Nl2sqlRateLimiter {

    private final Cache<Long, AtomicInteger> counters;
    private final int limit;

    public Nl2sqlRateLimiter(Nl2sqlProperties properties) {
        this.limit = Math.max(1, properties.getRateLimitPerMinute());
        this.counters = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();
    }

    public void check(Long userId) {
        AtomicInteger counter = counters.get(userId, ignored -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }
}
