package com.sundry.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 模型调用闸门：限制在途请求数；遇到 429 后短时间直接降级，不再打供应商。
 * 配置了全局并发时用 Redis 租约；否则退化为单实例 Semaphore。
 */
@Component
public class AiLlmGate {

    private static final Logger log = LoggerFactory.getLogger(AiLlmGate.class);

    static final String COOLDOWN_KEY = "feed:ai:llm:cooldown";
    static final String LEASE_KEY = "feed:ai:llm:leases";

    /**
     * 清理过期租约后尝试占用一个全局名额；统一用 Redis 时间，避免实例时钟偏差。
     * ARGV[1]=limit, ARGV[2]=leaseTtlMs, ARGV[3]=member。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_LEASE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local limit = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZCARD', KEYS[1]) >= limit then
                return 0
            end
            redis.call('ZADD', KEYS[1], now + ttl, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ttl + 1000)
            return 1
            """, Long.class);

    private final Semaphore permits;
    private final long acquireTimeoutMillis;
    private final long cooldownMillis;
    private final long leaseTtlMillis;
    private final int globalConcurrency;
    private final StringRedisTemplate redis;

    private volatile long cooldownUntil;

    @Autowired
    public AiLlmGate(AiProperties properties, StringRedisTemplate redis) {
        this.permits = new Semaphore(Math.max(1, properties.getFeedLlmMaxConcurrent()));
        this.acquireTimeoutMillis = Math.max(1, properties.getFeedLlmAcquireTimeout().toMillis());
        this.cooldownMillis = Math.max(1, properties.getFeedLlmCooldown().toMillis());
        this.leaseTtlMillis = Math.max(1, properties.getFeedLlmLeaseTtl().toMillis());
        this.globalConcurrency = Math.max(0, properties.getFeedLlmGlobalConcurrency());
        this.redis = redis;
    }

    /**
     * 单测用：不接 Redis，退化为本实例 Semaphore + 本地冷却。
     */
    AiLlmGate(AiProperties properties) {
        this(properties, null);
    }

    public void ensureReady() {
        if (System.currentTimeMillis() < cooldownUntil || redisCooling()) {
            throw new AiFeedException("AI_COOLDOWN");
        }
    }

    /**
     * 返回租约标识；调用方必须在 finally 中释放。
     */
    public String acquire() {
        if (globalConcurrency > 0 && redis != null) {
            return acquireGlobal();
        }
        try {
            if (!permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new AiFeedException("AI_BUSY");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiFeedException("AI_INTERRUPTED", ex);
        }
        return "local";
    }

    public void release(String lease) {
        if (lease != null && lease.startsWith("redis:")) {
            try {
                redis.opsForZSet().remove(LEASE_KEY, lease.substring("redis:".length()));
            } catch (RuntimeException ex) {
                log.warn("释放全局 AI 租约失败 lease={}", lease, ex);
            }
            return;
        }
        permits.release();
    }

    public void coolDown() {
        cooldownUntil = System.currentTimeMillis() + cooldownMillis;
        if (redis != null) {
            try {
                redis.opsForValue().set(COOLDOWN_KEY, "1", Duration.ofMillis(cooldownMillis));
            } catch (RuntimeException ex) {
                // Redis 不可用时保留本实例冷却，避免继续打供应商
                log.warn("写入全局 AI 冷却失败，仅本实例生效", ex);
            }
        }
    }

    public boolean cooling() {
        return System.currentTimeMillis() < cooldownUntil || redisCooling();
    }

    private boolean redisCooling() {
        if (redis == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(COOLDOWN_KEY));
        } catch (RuntimeException ex) {
            log.warn("读取全局 AI 冷却失败，按本实例状态判断", ex);
            return false;
        }
    }

    private String acquireGlobal() {
        long deadline = System.currentTimeMillis() + acquireTimeoutMillis;
        do {
            String member = UUID.randomUUID().toString();
            Long ok = redis.execute(ACQUIRE_LEASE, List.of(LEASE_KEY),
                    String.valueOf(globalConcurrency),
                    String.valueOf(leaseTtlMillis),
                    member);
            if (ok != null && ok == 1L) {
                return "redis:" + member;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AiFeedException("AI_INTERRUPTED", ex);
            }
        } while (System.currentTimeMillis() < deadline);
        throw new AiFeedException("AI_BUSY");
    }
}
