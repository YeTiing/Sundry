package com.sundry.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiLlmGateTest {

    @Test
    void limitsConcurrentCalls() {
        AiLlmGate gate = gate(1, 50, 1_000);

        String first = gate.acquire();
        assertThatThrownBy(gate::acquire)
                .isInstanceOf(AiFeedException.class)
                .hasMessage("AI_BUSY");

        gate.release(first);
        assertThatCode(() -> {
            String lease = gate.acquire();
            gate.release(lease);
        }).doesNotThrowAnyException();
    }

    @Test
    void cooldownRejectsCallsUntilExpired() throws InterruptedException {
        AiLlmGate gate = gate(1, 50, 50);

        gate.coolDown();
        assertThatThrownBy(gate::ensureReady)
                .isInstanceOf(AiFeedException.class)
                .hasMessage("AI_COOLDOWN");

        Thread.sleep(80);
        assertThatCode(gate::ensureReady).doesNotThrowAnyException();
    }

    private AiLlmGate gate(int permits, long timeoutMillis, long cooldownMillis) {
        AiProperties properties = new AiProperties();
        properties.setFeedLlmMaxConcurrent(permits);
        properties.setFeedLlmAcquireTimeout(Duration.ofMillis(timeoutMillis));
        properties.setFeedLlmCooldown(Duration.ofMillis(cooldownMillis));
        return new AiLlmGate(properties);
    }
}
