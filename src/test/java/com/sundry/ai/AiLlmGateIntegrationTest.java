package com.sundry.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多实例场景：全局并发租约和 429 冷却都走 Redis。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiLlmGateIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.delete(AiLlmGate.LEASE_KEY);
        redis.delete(AiLlmGate.COOLDOWN_KEY);
    }

    @Test
    void globalConcurrencyLeaseIsShared() {
        AiLlmGate first = globalGate();
        AiLlmGate second = globalGate();

        String lease = first.acquire();
        assertThatThrownBy(second::acquire)
                .isInstanceOf(AiFeedException.class)
                .hasMessage("AI_BUSY");

        first.release(lease);
        assertThatCode(() -> {
            String next = second.acquire();
            second.release(next);
        }).doesNotThrowAnyException();
    }

    @Test
    void cooldownIsVisibleAcrossInstances() {
        AiLlmGate first = globalGate();
        first.coolDown();

        assertThat(redis.hasKey(AiLlmGate.COOLDOWN_KEY)).isTrue();
        assertThatThrownBy(globalGate()::ensureReady)
                .isInstanceOf(AiFeedException.class)
                .hasMessage("AI_COOLDOWN");
    }

    private AiLlmGate globalGate() {
        AiProperties properties = new AiProperties();
        properties.setFeedLlmGlobalConcurrency(1);
        properties.setFeedLlmAcquireTimeout(Duration.ofMillis(100));
        properties.setFeedLlmLeaseTtl(Duration.ofSeconds(10));
        properties.setFeedLlmCooldown(Duration.ofSeconds(30));
        return new AiLlmGate(properties, redis);
    }
}
