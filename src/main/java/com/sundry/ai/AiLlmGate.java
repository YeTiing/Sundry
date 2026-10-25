package com.sundry.ai;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 模型调用闸门：限制单实例在途请求数；遇到 429 后短时间直接降级，不再打供应商。
 */
@Component
public class AiLlmGate {

    private final Semaphore permits;
    private final long acquireTimeoutMillis;
    private final long cooldownMillis;

    private volatile long cooldownUntil;

    public AiLlmGate(AiProperties properties) {
        this.permits = new Semaphore(Math.max(1, properties.getFeedLlmMaxConcurrent()));
        this.acquireTimeoutMillis = Math.max(1, properties.getFeedLlmAcquireTimeout().toMillis());
        this.cooldownMillis = Math.max(1, properties.getFeedLlmCooldown().toMillis());
    }

    public void ensureReady() {
        if (System.currentTimeMillis() < cooldownUntil) {
            throw new AiFeedException("AI_COOLDOWN");
        }
    }

    public void acquire() {
        try {
            if (!permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new AiFeedException("AI_BUSY");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiFeedException("AI_INTERRUPTED", ex);
        }
    }

    public void release() {
        permits.release();
    }

    public void coolDown() {
        cooldownUntil = System.currentTimeMillis() + cooldownMillis;
    }

    public boolean cooling() {
        return System.currentTimeMillis() < cooldownUntil;
    }
}
