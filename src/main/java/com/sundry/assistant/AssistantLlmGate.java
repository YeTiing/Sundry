package com.sundry.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 上游 429 冷却：写在 Redis，多实例共享。
 * Redis 不可用时放行，避免助手整条链路不可用。
 */
@Component
public class AssistantLlmGate {

    private static final Logger log = LoggerFactory.getLogger(AssistantLlmGate.class);
    private static final String KEY = "assistant:llm:cooldown";

    private final StringRedisTemplate redis;
    private final Duration cooldown;

    public AssistantLlmGate(StringRedisTemplate redis,
                            @Value("${app.assistant.llm-cooldown:30s}") Duration cooldown) {
        this.redis = redis;
        this.cooldown = cooldown == null || cooldown.isNegative()
                ? Duration.ofSeconds(30) : cooldown;
    }

    public void ensureReady() {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(KEY))) {
                throw new AssistantException("AI_COOLDOWN");
            }
        } catch (AssistantException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("助手冷却检查失败，放行本次请求", ex);
        }
    }

    public void coolDown() {
        try {
            redis.opsForValue().set(KEY, "1", cooldown);
        } catch (RuntimeException ex) {
            log.warn("助手冷却写入失败", ex);
        }
    }
}
