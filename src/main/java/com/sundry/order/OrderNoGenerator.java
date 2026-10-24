package com.sundry.order;

import org.springframework.stereotype.Component;

/**
 * 雪花订单号：41 位时间 + 10 位 workerId + 12 位序列。
 * 多实例部署时每个实例的 workerId 必须不同。
 */
@Component
public class OrderNoGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public OrderNoGenerator(OrderProperties properties) {
        long configured = properties.getWorkerId();
        if (configured < 0 || configured > MAX_WORKER_ID) {
            throw new IllegalStateException("app.order.worker-id 必须在 0 到 1023 之间");
        }
        this.workerId = configured;
    }

    public synchronized String next() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            long offset = lastTimestamp - now;
            if (offset > 5) {
                throw new IllegalStateException("系统时钟回拨，拒绝生成订单号");
            }
            sleep(offset + 1);
            now = System.currentTimeMillis();
            if (now < lastTimestamp) {
                throw new IllegalStateException("系统时钟回拨，拒绝生成订单号");
            }
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = now;

        long id = ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
        return Long.toString(id);
    }

    private long waitNextMillis(long last) {
        long now = System.currentTimeMillis();
        while (now <= last) {
            now = System.currentTimeMillis();
        }
        return now;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("生成订单号时线程被中断", ex);
        }
    }
}
