package com.sundry.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * AI 推流的状态存储：待处理标记、队列、处理中列表、快照和用户锁。
 * 和浏览行为落库使用同一套 Redis 队列模式。
 */
@Component
public class AiFeedStore {

    private static final Logger log = LoggerFactory.getLogger(AiFeedStore.class);

    public static final String QUEUE_KEY = "feed:ai:queue";
    public static final String PROCESSING_KEY = "feed:ai:processing";

    private static final String SNAPSHOT_KEY = "feed:ai:snapshot:%d";
    private static final String PENDING_KEY = "feed:ai:pending:%d";
    private static final String QUEUED_KEY = "feed:ai:queued:%d";
    private static final String ATTEMPT_KEY = "feed:ai:attempt:%d";
    private static final String LOCK_KEY = "feed:ai:lock:%d";

    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration ATTEMPT_TTL = Duration.ofMinutes(5);

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public AiFeedStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                       AiProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 登记一次重排。返回 buildId；队列满时返回 null。
     */
    public String enqueue(Long userId) {
        Long size = redis.opsForList().size(QUEUE_KEY);
        if (size != null && size >= properties.getFeedQueueMax()) {
            log.warn("AI 队列已满，跳过 userId={} size={}", userId, size);
            return null;
        }
        String buildId = UUID.randomUUID().toString();
        redis.opsForValue().set(pendingKey(userId), buildId, properties.getFeedPendingTtl());
        Boolean first = redis.opsForValue()
                .setIfAbsent(queuedKey(userId), buildId, properties.getFeedPendingTtl());
        if (Boolean.TRUE.equals(first)) {
            redis.opsForList().leftPush(QUEUE_KEY, String.valueOf(userId));
        }
        return buildId;
    }

    public String pending(Long userId) {
        return redis.opsForValue().get(pendingKey(userId));
    }

    public void clearPending(Long userId) {
        redis.delete(pendingKey(userId));
    }

    public void clearQueued(Long userId) {
        redis.delete(queuedKey(userId));
    }

    public FeedSnapshot snapshot(Long userId) {
        String json = redis.opsForValue().get(snapshotKey(userId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FeedSnapshot.class);
        } catch (RuntimeException ex) {
            log.warn("AI 快照解析失败，已删除 userId={}", userId, ex);
            redis.delete(snapshotKey(userId));
            return null;
        }
    }

    public void saveSnapshot(Long userId, FeedSnapshot snapshot) {
        redis.opsForValue().set(snapshotKey(userId),
                objectMapper.writeValueAsString(snapshot),
                properties.getFeedCacheTtl());
    }

    public long nextAttempt(Long userId) {
        Long count = redis.opsForValue().increment(attemptKey(userId));
        redis.expire(attemptKey(userId), ATTEMPT_TTL);
        return count == null ? 1 : count;
    }

    public void clearAttempts(Long userId) {
        redis.delete(attemptKey(userId));
    }

    public boolean lock(Long userId, String token) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(lockKey(userId), token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void unlock(Long userId, String token) {
        redis.execute(RELEASE_LOCK, List.of(lockKey(userId)), token);
    }

    public String moveToProcessing() {
        return redis.opsForList().rightPopAndLeftPush(QUEUE_KEY, PROCESSING_KEY);
    }

    public List<String> processing(int limit) {
        return redis.opsForList().range(PROCESSING_KEY, 0, limit - 1L);
    }

    public void removeProcessing(String value) {
        redis.opsForList().remove(PROCESSING_KEY, 1, value);
    }

    public long queueSize() {
        Long size = redis.opsForList().size(QUEUE_KEY);
        return size == null ? 0 : size;
    }

    private String snapshotKey(Long userId) {
        return SNAPSHOT_KEY.formatted(userId);
    }

    private String pendingKey(Long userId) {
        return PENDING_KEY.formatted(userId);
    }

    private String queuedKey(Long userId) {
        return QUEUED_KEY.formatted(userId);
    }

    private String attemptKey(Long userId) {
        return ATTEMPT_KEY.formatted(userId);
    }

    private String lockKey(Long userId) {
        return LOCK_KEY.formatted(userId);
    }
}
