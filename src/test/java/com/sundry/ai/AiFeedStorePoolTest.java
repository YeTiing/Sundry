package com.sundry.ai;

import com.sundry.ai.FeedSnapshot.SnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 变体环形池：切换顺序、过期剔除、已曝光记录和并发原子性。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiFeedStorePoolTest {

    private static final long USER_ID = 990000001L;

    @Autowired
    private AiFeedStore store;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.delete(List.of(poolKey(), seenKey()));
    }

    @Test
    void switchRotatesRingAndWrapsAround() {
        store.pushVariants(USER_ID, List.of(variant("A", LocalDateTime.now(), 1L, 2L),
                variant("B", LocalDateTime.now(), 3L)));

        assertThat(store.poolSize(USER_ID)).isEqualTo(2);
        assertThat(store.switchVariant(USER_ID).buildId()).isEqualTo("A");
        assertThat(store.switchVariant(USER_ID).buildId()).isEqualTo("B");
        assertThat(store.switchVariant(USER_ID).buildId()).isEqualTo("A");
        assertThat(store.poolSize(USER_ID)).isEqualTo(2);
        assertThat(redis.getExpire(poolKey())).isPositive();
    }

    @Test
    void expiredVariantIsSkippedAndRemoved() {
        FeedSnapshot expired = variant("OLD", LocalDateTime.now().minusMinutes(21), 1L);
        FeedSnapshot fresh = variant("NEW", LocalDateTime.now(), 2L);
        store.pushVariants(USER_ID, List.of(expired, fresh));

        assertThat(store.switchVariant(USER_ID).buildId()).isEqualTo("NEW");
        assertThat(store.poolSize(USER_ID)).isEqualTo(1);
        assertThat(store.switchVariant(USER_ID).buildId()).isEqualTo("NEW");
    }

    @Test
    void allExpiredVariantsReturnEmptyPool() {
        store.pushVariants(USER_ID, List.of(
                variant("OLD1", LocalDateTime.now().minusMinutes(30), 1L),
                variant("OLD2", LocalDateTime.now().minusMinutes(21), 2L)));

        assertThat(store.switchVariant(USER_ID)).isNull();
        assertThat(store.poolSize(USER_ID)).isZero();
    }

    @Test
    void switchedVariantIsRecordedAsSeen() {
        store.pushVariants(USER_ID, List.of(variant("A", LocalDateTime.now(), 11L, 12L)));

        store.switchVariant(USER_ID);

        assertThat(redis.opsForZSet().score(seenKey(), "11")).isNotNull();
        assertThat(redis.opsForZSet().score(seenKey(), "12")).isNotNull();
        assertThat(redis.getExpire(seenKey())).isPositive();
    }

    @Test
    void concurrentSwitchesNeverRepeatInsideOneRound() throws Exception {
        store.pushVariants(USER_ID, List.of(variant("A", LocalDateTime.now(), 1L),
                variant("B", LocalDateTime.now(), 2L)));

        int calls = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                tasks.add(() -> store.switchVariant(USER_ID).buildId());
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            int a = 0;
            int b = 0;
            for (Future<String> future : futures) {
                String buildId = future.get();
                if ("A".equals(buildId)) {
                    a++;
                } else if ("B".equals(buildId)) {
                    b++;
                }
            }
            assertThat(a).isEqualTo(calls / 2);
            assertThat(b).isEqualTo(calls / 2);
        } finally {
            pool.shutdownNow();
        }
    }

    private FeedSnapshot variant(String buildId, LocalDateTime builtAt, Long... ids) {
        List<SnapshotItem> items = Arrays.stream(ids)
                .map(id -> new SnapshotItem(id, "推荐"))
                .toList();
        return new FeedSnapshot(buildId, builtAt, "test-model", "feed_rerank_v1", items);
    }

    private String poolKey() {
        return "feed:ai:pool:" + USER_ID;
    }

    private String seenKey() {
        return "feed:ai:pool:seen:" + USER_ID;
    }
}
