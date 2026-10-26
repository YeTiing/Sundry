package com.sundry.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
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

    private static final String PENDING_KEY = "feed:ai:pending:%d";
    private static final String QUEUED_KEY = "feed:ai:queued:%d";
    private static final String ATTEMPT_KEY = "feed:ai:attempt:%d";
    private static final String LOCK_KEY = "feed:ai:lock:%d";
    private static final String POOL_KEY = "feed:ai:pool:%d";
    private static final String POOL_NEXT_KEY = "feed:ai:pool:next:%d";
    private static final String POOL_SEEN_KEY = "feed:ai:pool:seen:%d";
    private static final String POOL_ROUND_KEY = "feed:ai:pool:round:%d";
    private static final String POOL_CURRENT_KEY = "feed:ai:pool:current:%d";
    private static final String BUILD_COOLDOWN_KEY = "feed:ai:build:cooldown:%d";
    private static final String BUILD_HOURLY_KEY = "feed:ai:build:hourly:%d";
    public static final String BUILD_BUCKET_FIRST_KEY = "feed:ai:build:bucket:first";
    public static final String BUILD_BUCKET_SHARED_KEY = "feed:ai:build:bucket:shared";
    private static final String SEED_LOCK_KEY = "feed:ai:seed:%d";

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
     * 令牌桶：ARGV[1]=容量，ARGV[2]=每毫秒补充的令牌数。
     * 只有真正拿到令牌才写回 tokens-1，被拒绝的请求不会消耗额度。
     */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
                tokens = capacity
                ts = nowMs
            end
            local elapsed = nowMs - ts
            if elapsed < 0 then
                elapsed = 0
            end
            tokens = math.min(capacity, tokens + elapsed * rate)
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', nowMs)
                redis.call('PEXPIRE', KEYS[1], 120000)
                return 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', nowMs)
            redis.call('PEXPIRE', KEYS[1], 120000)
            return 0
            """, Long.class);

    /**
     * 每用户每小时构建计数；只在授予构建许可后调用。
     */
    private static final DefaultRedisScript<Long> INCR_HOURLY = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return count
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
     * 用下沉后的顺序替换池中的同一份变体。
     */
    private static final DefaultRedisScript<Long> REPLACE_ENTRY = new DefaultRedisScript<>("""
            local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
            if removed > 0 then
                redis.call('RPUSH', KEYS[1], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

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
    private final MeterRegistry meterRegistry;

    public AiFeedStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                       AiProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
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
        Boolean first = redis.opsForValue()
                .setIfAbsent(queuedKey(userId), buildId, properties.getFeedQueueTtl());
        if (!Boolean.TRUE.equals(first)) {
            // 已有在途构建，不覆盖 pending，避免把正常构建判成 STALE
            return redis.opsForValue().get(pendingKey(userId));
        }
        redis.opsForValue().set(pendingKey(userId), buildId, properties.getFeedQueueTtl());
        redis.opsForList().leftPush(QUEUE_KEY, String.valueOf(userId));
        return buildId;
    }

    public boolean tryBuildPermit(Long userId) {
        return tryBuildPermit(userId, BuildReason.REFILL);
    }

    /**
     * 构建许可：用户冷却 + 每小时配额 + 全局令牌桶（首访有保底额度）。
     * 只有真正授予时才扣额度；被拒绝的请求不会把全局计数打满。
     */
    public boolean tryBuildPermit(Long userId, BuildReason reason) {
        // 冷却 key 同时做用户级互斥：拿不到说明 10 秒内已经尝试过
        Boolean gate = redis.opsForValue().setIfAbsent(cooldownKey(userId), "1",
                properties.getFeedBuildCooldown());
        if (!Boolean.TRUE.equals(gate)) {
            recordPermit("rejected", "cooldown", reason);
            return false;
        }

        int hourlyLimit = Math.max(0, properties.getFeedBuildPerUserHourly());
        if (hourlyLimit > 0) {
            String value = redis.opsForValue().get(hourlyKey(userId));
            int used = value == null ? 0 : Integer.parseInt(value);
            if (used >= hourlyLimit) {
                shortenCooldown(userId);
                recordPermit("rejected", "hourly_limit", reason);
                return false;
            }
        }

        int total = Math.max(1, properties.getFeedBuildRateLimit());
        int percent = Math.min(100, Math.max(0, properties.getFeedBuildReservePercent()));
        int firstCapacity = (int) Math.ceil(total * percent / 100.0);
        if (percent > 0) {
            firstCapacity = Math.max(1, firstCapacity);
        }
        firstCapacity = Math.min(firstCapacity, total);
        int sharedCapacity = total - firstCapacity;

        boolean granted = reason == BuildReason.COLD_START
                && tryTakeToken(BUILD_BUCKET_FIRST_KEY, firstCapacity);
        if (!granted && sharedCapacity > 0) {
            granted = tryTakeToken(BUILD_BUCKET_SHARED_KEY, sharedCapacity);
        }
        if (!granted) {
            shortenCooldown(userId);
            recordPermit("rejected", "global_limit", reason);
            return false;
        }

        if (hourlyLimit > 0) {
            redis.execute(INCR_HOURLY, List.of(hourlyKey(userId)), "3600");
        }
        recordPermit("granted", "ok", reason);
        return true;
    }

    private void shortenCooldown(Long userId) {
        Duration reject = properties.getFeedBuildRejectCooldown();
        if (reject != null && !reject.isZero() && !reject.isNegative()) {
            redis.expire(cooldownKey(userId), reject);
        }
    }

    private boolean tryTakeToken(String key, int capacity) {
        if (capacity <= 0) {
            return false;
        }
        Long result = redis.execute(TOKEN_BUCKET, List.of(key),
                String.valueOf(capacity), String.valueOf(capacity / 60_000.0));
        return result != null && result == 1L;
    }

    private void recordPermit(String result, String reason, BuildReason buildReason) {
        Counter.builder("sundry.feed.ai.build.permit")
                .tag("result", result)
                .tag("reason", reason)
                .tag("buildReason", buildReason.name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * 首访播种锁，避免同一用户多个窗口同时算规则、重复写入。
     */
    public String trySeedLock(Long userId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(seedLockKey(userId), token, Duration.ofSeconds(10));
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void releaseSeedLock(Long userId, String token) {
        redis.execute(RELEASE_LOCK, List.of(seedLockKey(userId)), token);
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

    /**
     * 把一批变体写入环形池。列表整体按变体 TTL 过期。
     */
    public void pushVariants(Long userId, List<FeedSnapshot> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        List<String> payloads = new ArrayList<>(variants.size());
        for (FeedSnapshot variant : variants) {
            payloads.add(encode(variant));
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
            String raw = redis.execute(SWITCH_VARIANT, List.of(key));
            if (raw == null) {
                return null;
            }
            PoolEntry entry = decode(raw);
            if (entry == null || isExpired(entry)) {
                redis.opsForList().remove(key, 1, raw);
                continue;
            }
            FeedSnapshot variant = parseVariant(entry.json());
            if (variant == null) {
                redis.opsForList().remove(key, 1, raw);
                log.warn("AI 变体解析失败，已从池中移除 userId={}", userId);
                continue;
            }
            FeedSnapshot viewed = sinkSeen(userId, variant);
            if (viewed != variant) {
                redis.execute(REPLACE_ENTRY, List.of(key), raw, encode(viewed));
            }
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
            args.add(encode(variant));
        }
        redis.execute(REPLACE_LIST, List.of(nextPoolKey(userId)), args.toArray());
    }

    public long nextPoolSize(Long userId) {
        Long size = redis.opsForList().size(nextPoolKey(userId));
        return size == null ? 0 : size;
    }

    /**
     * 待上架区是否有完整、且首份是真实 AI 的批次；用于临时变体的自动升级。
     */
    public boolean hasPromotableNextBatch(Long userId) {
        List<String> values = redis.opsForList().range(nextPoolKey(userId), 0, -1);
        if (values == null || values.isEmpty()) {
            return false;
        }
        long usable = 0;
        Boolean firstReal = null;
        for (String raw : values) {
            PoolEntry entry = decode(raw);
            if (entry == null || isExpired(entry)) {
                continue;
            }
            usable++;
            if (firstReal == null) {
                FeedSnapshot variant = parseVariant(entry.json());
                firstReal = variant != null && !variant.ruleFallback();
            }
        }
        return usable >= properties.getEffectiveFeedVariantCount()
                && Boolean.TRUE.equals(firstReal);
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
        String fallbackJson = null;
        for (String raw : values) {
            PoolEntry entry = decode(raw);
            if (entry == null || isExpired(entry)) {
                redis.opsForList().remove(key, 1, raw);
                continue;
            }
            if (fallbackJson == null) {
                fallbackJson = entry.json();
            }
            if (target != null && target.equals(entry.buildId())) {
                return parseVariant(entry.json());
            }
        }
        return fallbackJson == null ? null : parseVariant(fallbackJson);
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
        for (String raw : values) {
            PoolEntry entry = decode(raw);
            if (entry != null && !isExpired(entry)) {
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

    private String encode(FeedSnapshot variant) {
        long builtAt = variant.builtAt() == null
                ? 0
                : variant.builtAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return variant.buildId() + '|' + builtAt + '|' + objectMapper.writeValueAsString(variant);
    }

    /**
     * 元素前缀带 buildId 和构建时间，计数/查找不用解析整段 JSON；兼容旧的纯 JSON 元素。
     */
    private PoolEntry decode(String raw) {
        int first = raw.indexOf('|');
        int second = first < 0 ? -1 : raw.indexOf('|', first + 1);
        if (first > 0 && second > first) {
            try {
                long builtAt = Long.parseLong(raw.substring(first + 1, second));
                return new PoolEntry(raw.substring(0, first), builtAt,
                        raw.substring(second + 1));
            } catch (NumberFormatException ex) {
                // 旧格式，退回 JSON 解析
            }
        }
        FeedSnapshot variant = parseVariant(raw);
        if (variant == null || variant.builtAt() == null) {
            return null;
        }
        long builtAt = variant.builtAt().atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        return new PoolEntry(variant.buildId(), builtAt, raw);
    }

    private boolean isExpired(PoolEntry entry) {
        return System.currentTimeMillis() - entry.builtAtMillis()
                > properties.getFeedVariantTtl().toMillis();
    }

    private record PoolEntry(String buildId, long builtAtMillis, String json) {
    }

    /**
     * 记录本次真正返回给用户的商品，而不是整份变体。
     */
    public void recordSeen(Long userId, Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        String key = poolSeenKey(userId);
        double score = System.currentTimeMillis();
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (Long id : productIds) {
            if (id != null) {
                tuples.add(new DefaultTypedTuple<>(String.valueOf(id), score));
            }
        }
        if (!tuples.isEmpty()) {
            redis.opsForZSet().add(key, tuples);
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

    private String cooldownKey(Long userId) {
        return BUILD_COOLDOWN_KEY.formatted(userId);
    }

    private String hourlyKey(Long userId) {
        return BUILD_HOURLY_KEY.formatted(userId);
    }

    private String seedLockKey(Long userId) {
        return SEED_LOCK_KEY.formatted(userId);
    }

}
