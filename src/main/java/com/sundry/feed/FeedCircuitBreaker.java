package com.sundry.feed;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 读取熔断：连续失败后短时间直接走本地兜底，避免每个请求都实时算规则。
 */
@Component
public class FeedCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_MILLIS = 30_000;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile long openUntil;

    public boolean allow() {
        return System.currentTimeMillis() >= openUntil;
    }

    public void success() {
        failures.set(0);
        openUntil = 0;
    }

    public void failure() {
        if (failures.incrementAndGet() >= FAILURE_THRESHOLD) {
            openUntil = System.currentTimeMillis() + OPEN_MILLIS;
        }
    }
}
