package com.sundry.assistant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AssistantRateLimiter {

    private final Cache<Long, AtomicInteger> counters;
    private final int limit;

    public AssistantRateLimiter(
            @Value("${app.assistant.rate-limit-per-minute:10}") int limit) {
        this.limit = Math.max(1, limit);
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
