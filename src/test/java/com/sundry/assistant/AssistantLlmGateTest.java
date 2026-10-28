package com.sundry.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantLlmGateTest {

    private static final String KEY = "assistant:llm:cooldown";

    @Test
    void rejectsWhileCooldownKeyExists() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(KEY)).thenReturn(true);
        AssistantLlmGate gate = new AssistantLlmGate(redis, Duration.ofSeconds(30));

        assertThatThrownBy(gate::ensureReady)
                .isInstanceOf(AssistantException.class);
    }

    @Test
    void writesCooldownWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        AssistantLlmGate gate = new AssistantLlmGate(redis, Duration.ofSeconds(30));

        gate.coolDown();

        verify(ops).set(eq(KEY), eq("1"), eq(Duration.ofSeconds(30)));
    }

    @Test
    void redisFailureFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(KEY)).thenThrow(new RuntimeException("redis down"));
        AssistantLlmGate gate = new AssistantLlmGate(redis, Duration.ofSeconds(30));

        assertThatCode(gate::ensureReady).doesNotThrowAnyException();
    }
}
