package com.sundry.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把 Redis 里的行为队列批量写入数据库。
 * processing 列表保留到落库成功，失败时下次先重试；event_id + INSERT IGNORE 保证幂等。
 */
@Component
public class BehaviorFlushProcessor {

    private static final Logger log = LoggerFactory.getLogger(BehaviorFlushProcessor.class);

    private static final String QUEUE_KEY = "behavior:queue";
    private static final String PROCESSING_KEY = "behavior:processing";
    private static final String LOCK_KEY = "behavior:flush:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final UserBehaviorMapper behaviorMapper;
    private final FeedProperties properties;

    public BehaviorFlushProcessor(StringRedisTemplate redis, UserBehaviorMapper behaviorMapper,
                                  FeedProperties properties) {
        this.redis = redis;
        this.behaviorMapper = behaviorMapper;
        this.properties = properties;
    }

    public void flush() {
        String token = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            int batchSize = Math.max(1, properties.getBehaviorBatchSize());
            for (int i = 0; i < batchSize; i++) {
                String value = redis.opsForList().rightPopAndLeftPush(QUEUE_KEY, PROCESSING_KEY);
                if (value == null) {
                    break;
                }
            }

            List<String> pending = redis.opsForList().range(PROCESSING_KEY, 0, -1);
            if (pending == null || pending.isEmpty()) {
                return;
            }

            List<UserBehavior> items = new ArrayList<>(pending.size());
            for (String value : pending) {
                try {
                    items.add(BehaviorEvent.decode(value).toEntity());
                } catch (RuntimeException ex) {
                    log.warn("丢弃格式错误的行为事件 {}", value, ex);
                }
            }
            if (!items.isEmpty()) {
                behaviorMapper.insertBatch(items);
            }
            redis.delete(PROCESSING_KEY);
        } catch (RuntimeException ex) {
            log.warn("行为批量落库失败，保留 processing 下次重试", ex);
        } finally {
            redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), token);
        }
    }
}
