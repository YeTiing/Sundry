package com.sundry.feed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeedCircuitBreakerTest {

    @Test
    void opensAfterThresholdAndResetsOnSuccess() {
        FeedCircuitBreaker breaker = new FeedCircuitBreaker();

        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        assertThat(breaker.allow()).isTrue();

        breaker.failure();
        assertThat(breaker.allow()).isFalse();

        breaker.success();
        assertThat(breaker.allow()).isTrue();
    }
}
