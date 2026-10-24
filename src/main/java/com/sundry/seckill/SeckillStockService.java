package com.sundry.seckill;

import com.sundry.order.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀库存在 Redis 里的预热、原子扣减和回补。
 * 库存 key 不存在时由数据库懒重建，重建期间用 SETNX 锁避免并发重复预热。
 */
@Service
public class SeckillStockService {

    public static final int OK = 1;
    public static final int NOT_READY = -1;
    public static final int LIMIT_REACHED = -2;
    public static final int SOLD_OUT = -3;

    private static final Logger log = LoggerFactory.getLogger(SeckillStockService.class);
    private static final String STOCK_KEY = "seckill:stock:%d";
    private static final String BUYER_KEY = "seckill:buyer:%d";
    private static final String WARM_LOCK_KEY = "seckill:warm:%d";
    private static final Duration WARM_LOCK_TTL = Duration.ofSeconds(10);
    private static final int WAIT_ITERATIONS = 20;
    private static final long WAIT_MILLIS = 50;

    /**
     * KEYS[1]=库存，KEYS[2]=购买者 Hash；ARGV[1]=userId，ARGV[2]=限购数。
     */
    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then
                return -1
            end
            local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
            if bought >= tonumber(ARGV[2]) then
                return -2
            end
            if tonumber(stock) < 1 then
                return -3
            end
            redis.call('DECR', KEYS[1])
            redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
            return 1
            """, Long.class);

    /**
     * 建单失败时回滚：库存加回，并移除本次预占的用户标记。
     */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('INCRBY', KEYS[1], ARGV[2])
            end
            local bought = redis.call('HINCRBY', KEYS[2], ARGV[1], -tonumber(ARGV[2]))
            if bought <= 0 then
                redis.call('HDEL', KEYS[2], ARGV[1])
            end
            return 1
            """, Long.class);

    /**
     * 取消订单时只还库存，保留购买标记，避免同一用户重复抢。
     */
    private static final DefaultRedisScript<Long> RESTORE_STOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;

    public SeckillStockService(StringRedisTemplate redis,
                               SeckillActivityMapper activityMapper,
                               OrderMapper orderMapper) {
        this.redis = redis;
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 应用启动后把进行中的活动补进 Redis，覆盖 Redis 重启的情况。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpActiveActivities() {
        for (SeckillActivity activity : activityMapper.findActiveForWarmUp()) {
            try {
                ensureWarm(activity);
            } catch (RuntimeException ex) {
                log.warn("秒杀活动预热失败 activityId={}", activity.getId(), ex);
            }
        }
    }

    public boolean ensureWarm(SeckillActivity activity) {
        if (exists(activity.getId())) {
            return true;
        }
        return warmUp(activity);
    }

    private boolean warmUp(SeckillActivity activity) {
        String lockKey = WARM_LOCK_KEY.formatted(activity.getId());
        String token = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, token, WARM_LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            // 其他实例正在预热，短暂等待它完成
            for (int i = 0; i < WAIT_ITERATIONS; i++) {
                if (exists(activity.getId())) {
                    return true;
                }
                sleep(WAIT_MILLIS);
            }
            return exists(activity.getId());
        }

        try {
            if (exists(activity.getId())) {
                return true;
            }
            int remaining = Math.max(0,
                    activity.getStock() - (activity.getSoldCount() == null ? 0 : activity.getSoldCount()));
            long ttl = ttlSeconds(activity);
            String stockKey = STOCK_KEY.formatted(activity.getId());
            String buyerKey = BUYER_KEY.formatted(activity.getId());

            redis.opsForValue().set(stockKey, String.valueOf(remaining), Duration.ofSeconds(ttl));
            redis.delete(buyerKey);
            List<Long> buyers = orderMapper.findSeckillBuyerIds(activity.getId());
            if (!buyers.isEmpty()) {
                Map<String, String> values = new HashMap<>();
                for (Long buyer : buyers) {
                    values.put(String.valueOf(buyer), "1");
                }
                redis.opsForHash().putAll(buyerKey, values);
            }
            redis.expire(buyerKey, Duration.ofSeconds(ttl));
            return true;
        } finally {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    public int tryDeduct(Long activityId, Long userId, int limit) {
        Long result = redis.execute(DEDUCT_SCRIPT,
                List.of(STOCK_KEY.formatted(activityId), BUYER_KEY.formatted(activityId)),
                String.valueOf(userId), String.valueOf(limit));
        return result == null ? NOT_READY : result.intValue();
    }

    public void rollbackReservation(Long activityId, Long userId, int count) {
        redis.execute(ROLLBACK_SCRIPT,
                List.of(STOCK_KEY.formatted(activityId), BUYER_KEY.formatted(activityId)),
                String.valueOf(userId), String.valueOf(count));
    }

    public void restoreStock(Long activityId, int count) {
        redis.execute(RESTORE_STOCK_SCRIPT,
                List.of(STOCK_KEY.formatted(activityId)), String.valueOf(count));
    }

    public void clear(Long activityId) {
        redis.delete(List.of(STOCK_KEY.formatted(activityId), BUYER_KEY.formatted(activityId)));
    }

    public int remaining(Long activityId) {
        String value = redis.opsForValue().get(STOCK_KEY.formatted(activityId));
        return value == null ? -1 : Integer.parseInt(value);
    }

    private boolean exists(Long activityId) {
        return Boolean.TRUE.equals(redis.hasKey(STOCK_KEY.formatted(activityId)));
    }

    private long ttlSeconds(SeckillActivity activity) {
        long seconds = Duration.between(LocalDateTime.now(), activity.getEndAt().plusDays(1)).getSeconds();
        return Math.max(60, seconds);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
