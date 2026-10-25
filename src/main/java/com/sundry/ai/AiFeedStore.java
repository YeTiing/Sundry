package com.sundry.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final String POOL_KEY = "feed:ai:pool:%d";
    private static final String POOL_NEXT_KEY = "feed:ai:pool:next:%d";
    private static final String POOL_SEEN_KEY = "feed:ai:pool:seen:%d";
    private static final String POOL_ROUND_KEY = "feed:ai:pool:round:%d";
    private static final String POOL_CURRENT_KEY = "feed:ai:pool:current:%d";
    private static final String VARIANT_VIEW_KEY = "feed:ai:variant:view:%d:%s";

    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration ATTEMPT_TTL = Duration.ofMinutes(5);
    private static final Duration POOL_SEEN_TTL = Duration.ofHours(1);
    private static final Duration SEEN_WINDOW = Duration.ofMinutes(10);

    private static final int SEEN_SINK_HEAD = 8;
    private static final int SEEN_SINK_FROM = 11;
    private static final int SEEN_SINK_TO = 20;

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /**
     * 环形切换：池头取出后放回池尾。多实例并发时每个请求拿到不同变体。
     */
    private static final DefaultRedisScript<String> SWITCH_VARIANT = new DefaultRedisScript<>("""
            local v = redis.call('LPOP', KEYS[1])
            if v then
                redis.call('RPUSH', KEYS[1], v)
            end
            return v
            """, String.class);

    /**
     * 覆盖式写入待上架区：ARGV[1] 是 TTL 秒数，其余是变体 JSON。
     */
    private static final DefaultRedisScript<Long> REPLACE_LIST = new DefaultRedisScript<>("""
            local ttl = tonumber(ARGV[1])
            redis.call('DEL', KEYS[1])
            for i = 2, #ARGV do
                redis.call('RPUSH', KEYS[1], ARGV[i])
            end
            redis.call('EXPIRE', KEYS[1], ttl)
            return #ARGV - 1
            """, Long.class);

    /**
     * 待上架区提升为正式池；没有待上架内容时返回 0。
     */
    private static final DefaultRedisScript<Long> PROMOTE_NEXT = new DefaultRedisScript<>("""
            if redis.call('LLEN', KEYS[1]) > 0 then
                redis.call('DEL', KEYS[2])
                redis.call('RENAME', KEYS[1], KEYS[2])
                return 1
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

    /**
     * 把一批变体写入环形池。列表整体按变体 TTL 过期。
     */
    public void pushVariants(Long userId, List<FeedSnapshot> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        List<String> payloads = new ArrayList<>(variants.size());
        for (FeedSnapshot variant : variants) {
            payloads.add(objectMapper.writeValueAsString(variant));
        }
        String key = poolKey(userId);
        redis.opsForList().rightPushAll(key, payloads);
        redis.expire(key, properties.getFeedVariantTtl());
    }

    /**
     * 原子切到下一份变体。过期或损坏的变体直接移除并继续取下一份；池空返回 null。
     */
    public FeedSnapshot switchVariant(Long userId) {
        String key = poolKey(userId);
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) {
            return null;
        }
        for (long i = 0; i < size; i++) {
            String json = redis.execute(SWITCH_VARIANT, List.of(key));
            if (json == null) {
                return null;
            }
            FeedSnapshot variant = parseVariant(json);
            if (variant == null) {
                redis.opsForList().remove(key, 1, json);
                log.warn("AI 变体解析失败，已从池中移除 userId={}", userId);
                continue;
            }
            if (isExpired(variant)) {
                redis.opsForList().remove(key, 1, json);
                continue;
            }
            FeedSnapshot viewed = sinkSeen(userId, variant);
            recordSeen(userId, variant);
            saveVariantView(userId, viewed);
            redis.opsForValue().set(currentKey(userId), variant.buildId(),
                    properties.getFeedVariantTtl());
            return viewed;
        }
        return null;
    }

    public long poolSize(Long userId) {
        Long size = redis.opsForList().size(poolKey(userId));
        return size == null ? 0 : size;
    }

    /**
     * 新一批变体先写待上架区，等用户下次点击时再提升，避免正在阅读的列表被替换。
     */
    public void pushNextVariants(Long userId, List<FeedSnapshot> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(variants.size() + 1);
        args.add(String.valueOf(Math.max(1, properties.getFeedVariantTtl().toSeconds())));
        for (FeedSnapshot variant : variants) {
            args.add(objectMapper.writeValueAsString(variant));
        }
        redis.execute(REPLACE_LIST, List.of(nextPoolKey(userId)), args.toArray());
    }

    public long nextPoolSize(Long userId) {
        Long size = redis.opsForList().size(nextPoolKey(userId));
        return size == null ? 0 : size;
    }

    /**
     * 待上架区原子替换正式池，并重置轮次计数。
     */
    public boolean promoteNext(Long userId) {
        Long promoted = redis.execute(PROMOTE_NEXT,
                List.of(nextPoolKey(userId), poolKey(userId)));
        if (promoted != null && promoted > 0) {
            resetRound(userId);
            return true;
        }
        return false;
    }

    /**
     * 按 buildId 查找变体；buildId 为空或未命中时返回第一份未过期变体。
     * 扫描时顺手移除过期或损坏的变体。
     */
    public FeedSnapshot findVariant(Long userId, String buildId) {
        String key = poolKey(userId);
        List<String> values = redis.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String target = buildId == null
                ? redis.opsForValue().get(currentKey(userId))
                : buildId;
        FeedSnapshot fallback = null;
        FeedSnapshot resolved = null;
        for (String json : values) {
            FeedSnapshot variant = parseVariant(json);
            if (variant == null || isExpired(variant)) {
                redis.opsForList().remove(key, 1, json);
                continue;
            }
            if (fallback == null) {
                fallback = variant;
            }
            if (target != null && target.equals(variant.buildId())) {
                resolved = variant;
                break;
            }
        }
        FeedSnapshot variant = resolved == null ? fallback : resolved;
        return variant == null ? null : viewOrSelf(userId, variant);
    }

    /**
     * 复用旧变体时，把最近曝光过的头部商品下沉到 12~20 位；顺序持久化到 view key，保证翻页一致。
     */
    private FeedSnapshot sinkSeen(Long userId, FeedSnapshot variant) {
        List<FeedSnapshot.SnapshotItem> original = variant.items();
        if (original == null || original.size() <= SEEN_SINK_HEAD) {
            return variant;
        }
        Set<String> seen = redis.opsForZSet().rangeByScore(poolSeenKey(userId),
                System.currentTimeMillis() - SEEN_WINDOW.toMillis(), Double.MAX_VALUE);
        if (seen == null || seen.isEmpty()) {
            return variant;
        }
        Set<Long> seenIds = new HashSet<>();
        for (String value : seen) {
            try {
                seenIds.add(Long.valueOf(value));
            } catch (NumberFormatException ex) {
                // 成员格式异常时跳过，不影响排序
            }
        }

        List<FeedSnapshot.SnapshotItem> items = new ArrayList<>(original);
        List<FeedSnapshot.SnapshotItem> sunk = new ArrayList<>();
        int examined = 0;
        int index = 0;
        while (examined < SEEN_SINK_HEAD && index < items.size()) {
            if (seenIds.contains(items.get(index).id())) {
                sunk.add(items.remove(index));
            } else {
                index++;
            }
            examined++;
        }
        int insert = Math.min(SEEN_SINK_FROM, items.size());
        for (FeedSnapshot.SnapshotItem item : sunk) {
            if (insert >= SEEN_SINK_TO) {
                break;
            }
            items.add(insert, item);
            insert++;
        }
        return new FeedSnapshot(variant.buildId(), variant.builtAt(), variant.model(),
                variant.promptVersion(), items, variant.strategy());
    }

    private void saveVariantView(Long userId, FeedSnapshot variant) {
        redis.opsForValue().set(viewKey(userId, variant.buildId()),
                objectMapper.writeValueAsString(variant),
                properties.getFeedVariantTtl());
    }

    private FeedSnapshot viewOrSelf(Long userId, FeedSnapshot variant) {
        String json = redis.opsForValue().get(viewKey(userId, variant.buildId()));
        if (json == null) {
            return variant;
        }
        FeedSnapshot view = parseVariant(json);
        if (view == null || isExpired(view)) {
            redis.delete(viewKey(userId, variant.buildId()));
            return variant;
        }
        return view;
    }

    public long usablePoolSize(Long userId) {
        return usableSize(poolKey(userId));
    }

    public long usableNextPoolSize(Long userId) {
        return usableSize(nextPoolKey(userId));
    }

    /**
     * 每次切换 +1；达到变体数时由调用方触发补算。
     */
    public long bumpRound(Long userId) {
        Long round = redis.opsForValue().increment(roundKey(userId));
        redis.expire(roundKey(userId), properties.getFeedVariantTtl());
        return round == null ? 1 : round;
    }

    public void resetRound(Long userId) {
        redis.delete(roundKey(userId));
    }

    public long attempts(Long userId) {
        String value = redis.opsForValue().get(attemptKey(userId));
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private long usableSize(String key) {
        List<String> values = redis.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return 0;
        }
        long count = 0;
        for (String json : values) {
            FeedSnapshot variant = parseVariant(json);
            if (variant != null && !isExpired(variant)) {
                count++;
            }
        }
        return count;
    }

    private FeedSnapshot parseVariant(String json) {
        try {
            return objectMapper.readValue(json, FeedSnapshot.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isExpired(FeedSnapshot variant) {
        if (variant.builtAt() == null) {
            return true;
        }
        return Duration.between(variant.builtAt(), LocalDateTime.now())
                .compareTo(properties.getFeedVariantTtl()) > 0;
    }

    private void recordSeen(Long userId, FeedSnapshot variant) {
        if (variant.items() == null || variant.items().isEmpty()) {
            return;
        }
        String key = poolSeenKey(userId);
        long now = System.currentTimeMillis();
        for (FeedSnapshot.SnapshotItem item : variant.items()) {
            if (item != null && item.id() != null) {
                redis.opsForZSet().add(key, String.valueOf(item.id()), now);
            }
        }
        redis.expire(key, POOL_SEEN_TTL);
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

    private String poolKey(Long userId) {
        return POOL_KEY.formatted(userId);
    }

    private String poolSeenKey(Long userId) {
        return POOL_SEEN_KEY.formatted(userId);
    }

    private String nextPoolKey(Long userId) {
        return POOL_NEXT_KEY.formatted(userId);
    }

    private String roundKey(Long userId) {
        return POOL_ROUND_KEY.formatted(userId);
    }

    private String currentKey(Long userId) {
        return POOL_CURRENT_KEY.formatted(userId);
    }

    private String viewKey(Long userId, String buildId) {
        return VARIANT_VIEW_KEY.formatted(userId, buildId);
    }
}
