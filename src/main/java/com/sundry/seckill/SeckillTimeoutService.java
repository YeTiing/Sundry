package com.sundry.seckill;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀订单的支付截止时间放在 Redis ZSET，worker 到期领取。
 * 领取脚本同时 ZREM，多个实例不会重复处理同一订单。
 */
@Service
public class SeckillTimeoutService {

    private static final String KEY = "seckill:timeout";
    private static final Duration KEY_TTL = Duration.ofDays(7);

    private static final DefaultRedisScript<String> POP_DUE_SCRIPT = new DefaultRedisScript<>("""
            local items = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1],
                                     'LIMIT', 0, tonumber(ARGV[2]))
            if #items == 0 then
                return ''
            end
            for i = 1, #items do
                redis.call('ZREM', KEYS[1], items[i])
            end
            return table.concat(items, ',')
            """, String.class);

    private final StringRedisTemplate redis;

    public SeckillTimeoutService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void add(String orderNo, LocalDateTime deadline) {
        long score = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        redis.opsForZSet().add(KEY, orderNo, score);
        redis.expire(KEY, KEY_TTL);
    }

    public List<String> popDue(long nowMillis, int batchSize) {
        String result = redis.execute(POP_DUE_SCRIPT, List.of(KEY),
                String.valueOf(nowMillis), String.valueOf(batchSize));
        if (result == null || result.isBlank()) {
            return List.of();
        }
        List<String> orderNos = new ArrayList<>();
        for (String item : result.split(",")) {
            if (!item.isBlank()) {
                orderNos.add(item);
            }
        }
        return orderNos;
    }
}
