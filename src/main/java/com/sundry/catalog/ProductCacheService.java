package com.sundry.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sundry.catalog.dto.CategoryView;
import com.sundry.catalog.dto.ProductView;
import com.sundry.common.api.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 商品浏览缓存：本地 Caffeine -> Redis -> DB。
 * 击穿用本地锁 + Redis SETNX 互斥重建；穿透缓存空值；雪崩用随机 TTL。
 * 库存只用于展示，下单/秒杀会重新校验。
 */
@Service
public class ProductCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheService.class);

    private static final String DETAIL_KEY = "cache:product:detail:%d";
    private static final String DETAIL_NULL_KEY = "cache:product:null:%d";
    private static final String LIST_KEY = "cache:product-ids:%s:%s:%d:%d";
    private static final String LIST_VERSION_KEY = "cache:products:version";
    private static final String CATEGORY_KEY = "cache:categories";
    private static final String LOCK_KEY = "cache:lock:%s";

    private static final Duration DETAIL_TTL = Duration.ofSeconds(60);
    private static final Duration DETAIL_NULL_TTL = Duration.ofSeconds(60);
    private static final Duration LIST_TTL = Duration.ofSeconds(60);
    private static final Duration CATEGORY_TTL = Duration.ofMinutes(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    private final Cache<Long, ProductView> detailLocal = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();
    private final Cache<String, ProductIdPage> listLocal = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();
    private final Cache<String, List<CategoryView>> categoryLocal = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public ProductCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * 返回 null 表示商品不存在，调用方负责转成 404。
     */
    public ProductView detail(Long id, Supplier<ProductView> loader) {
        ProductView local = detailLocal.getIfPresent(id);
        if (local != null) {
            return local;
        }
        String key = DETAIL_KEY.formatted(id);
        ProductView cached = read(key, ProductView.class);
        if (cached != null) {
            detailLocal.put(id, cached);
            return cached;
        }
        if (hasNullMarker(id)) {
            return null;
        }

        ReentrantLock lock = lockFor(key);
        if (!tryLocalLock(lock)) {
            return fallbackDetail(id, loader);
        }
        try {
            local = detailLocal.getIfPresent(id);
            if (local != null) {
                return local;
            }
            cached = read(key, ProductView.class);
            if (cached != null) {
                detailLocal.put(id, cached);
                return cached;
            }
            if (hasNullMarker(id)) {
                return null;
            }

            String token = UUID.randomUUID().toString();
            if (!tryDistributedLock(key, token)) {
                return fallbackDetail(id, loader);
            }
            try {
                local = detailLocal.getIfPresent(id);
                if (local != null) {
                    return local;
                }
                cached = read(key, ProductView.class);
                if (cached != null) {
                    detailLocal.put(id, cached);
                    return cached;
                }
                ProductView value = loader.get();
                if (value == null) {
                    write(DETAIL_NULL_KEY.formatted(id), "1", DETAIL_NULL_TTL);
                    return null;
                }
                write(key, value, jitter(DETAIL_TTL, 10_000));
                detailLocal.put(id, value);
                return value;
            } finally {
                unlock(key, token);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 列表只缓存商品 ID 和总数；卡片通过 {@link #detail(Long, Supplier)} 单独缓存。
     */
    public ProductIdPage idPage(Long categoryId, int page, int size,
                                Supplier<ProductIdPage> loader) {
        String key = LIST_KEY.formatted(version(), categoryId == null ? "all" : categoryId, page, size);
        ProductIdPage local = listLocal.getIfPresent(key);
        if (local != null) {
            return local;
        }
        ProductIdPage cached = read(key, ProductIdPage.class);
        if (cached != null) {
            listLocal.put(key, cached);
            return cached;
        }

        ReentrantLock lock = lockFor(key);
        if (!tryLocalLock(lock)) {
            return safeLoad(loader);
        }
        try {
            local = listLocal.getIfPresent(key);
            if (local != null) {
                return local;
            }
            cached = read(key, ProductIdPage.class);
            if (cached != null) {
                listLocal.put(key, cached);
                return cached;
            }
            String token = UUID.randomUUID().toString();
            if (!tryDistributedLock(key, token)) {
                return safeLoad(loader);
            }
            try {
                cached = read(key, ProductIdPage.class);
                if (cached != null) {
                    listLocal.put(key, cached);
                    return cached;
                }
                ProductIdPage value = loader.get();
                write(key, value, jitter(LIST_TTL, 15_000));
                listLocal.put(key, value);
                return value;
            } finally {
                unlock(key, token);
            }
        } finally {
            lock.unlock();
        }
    }

    public List<CategoryView> categories(Supplier<List<CategoryView>> loader) {
        String localKey = "all";
        List<CategoryView> local = categoryLocal.getIfPresent(localKey);
        if (local != null) {
            return local;
        }
        List<CategoryView> cached = read(CATEGORY_KEY, new TypeReference<>() {
        });
        if (cached != null) {
            categoryLocal.put(localKey, cached);
            return cached;
        }

        ReentrantLock lock = lockFor(CATEGORY_KEY);
        if (!tryLocalLock(lock)) {
            return safeLoad(loader);
        }
        try {
            local = categoryLocal.getIfPresent(localKey);
            if (local != null) {
                return local;
            }
            cached = read(CATEGORY_KEY, new TypeReference<>() {
            });
            if (cached != null) {
                categoryLocal.put(localKey, cached);
                return cached;
            }
            String token = UUID.randomUUID().toString();
            if (!tryDistributedLock(CATEGORY_KEY, token)) {
                return safeLoad(loader);
            }
            try {
                List<CategoryView> value = loader.get();
                write(CATEGORY_KEY, value, jitter(CATEGORY_TTL, 180_000));
                categoryLocal.put(localKey, value);
                return value;
            } finally {
                unlock(CATEGORY_KEY, token);
            }
        } finally {
            lock.unlock();
        }
    }

    public void evictDetail(Long id) {
        detailLocal.invalidate(id);
        safeDelete(DETAIL_KEY.formatted(id), DETAIL_NULL_KEY.formatted(id));
    }

    /**
     * 商品列表统一用版本号失效：旧版本的 key 不再被读取，靠 TTL 自然过期。
     */
    public void evictLists() {
        listLocal.invalidateAll();
        try {
            redis.opsForValue().increment(LIST_VERSION_KEY);
        } catch (RuntimeException ex) {
            log.warn("商品列表缓存版本递增失败", ex);
        }
    }

    public void evictCategories() {
        categoryLocal.invalidateAll();
        safeDelete(CATEGORY_KEY);
    }

    /**
     * 仅用于测试或运维手工清理，不参与业务逻辑。
     */
    public void clearLocalCaches() {
        detailLocal.invalidateAll();
        listLocal.invalidateAll();
        categoryLocal.invalidateAll();
    }

    private ProductView fallbackDetail(Long id, Supplier<ProductView> loader) {
        ProductView cached = read(DETAIL_KEY.formatted(id), ProductView.class);
        return cached != null ? cached : loader.get();
    }

    private boolean hasNullMarker(Long id) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(DETAIL_NULL_KEY.formatted(id)));
        } catch (RuntimeException ex) {
            log.warn("读取商品空值缓存失败 id={}", id, ex);
            return false;
        }
    }

    private String version() {
        try {
            String value = redis.opsForValue().get(LIST_VERSION_KEY);
            return value == null ? "0" : value;
        } catch (RuntimeException ex) {
            log.warn("读取商品列表缓存版本失败", ex);
            return "0";
        }
    }

    private <T> T read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (RuntimeException ex) {
            log.warn("读取缓存失败 key={}", key, ex);
            safeDelete(key);
            return null;
        }
    }

    private <T> T read(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (RuntimeException ex) {
            log.warn("读取缓存失败 key={}", key, ex);
            safeDelete(key);
            return null;
        }
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException ex) {
            log.warn("写入缓存失败 key={}", key, ex);
        }
    }

    private void safeDelete(String... keys) {
        try {
            redis.delete(List.of(keys));
        } catch (RuntimeException ex) {
            log.warn("删除缓存失败 keys={}", String.join(",", keys), ex);
        }
    }

    private boolean tryLocalLock(ReentrantLock lock) {
        try {
            return lock.tryLock(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean tryDistributedLock(String key, String token) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(LOCK_KEY.formatted(key), token, LOCK_TTL));
        } catch (RuntimeException ex) {
            log.warn("获取缓存重建锁失败 key={}", key, ex);
            return false;
        }
    }

    private void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY.formatted(key)), token);
        } catch (RuntimeException ex) {
            log.warn("释放缓存重建锁失败 key={}", key, ex);
        }
    }

    private <T> T safeLoad(Supplier<T> loader) {
        return loader.get();
    }

    private Duration jitter(Duration base, long jitterMillis) {
        long delta = ThreadLocalRandom.current().nextLong(-jitterMillis, jitterMillis + 1);
        return base.plusMillis(delta);
    }

    private ReentrantLock lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
