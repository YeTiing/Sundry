package com.sundry.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首页秒杀运营位：只展示进行中、已开始、商品在售且还有配额的活动。
 */
@SpringBootTest(properties = "app.feed.featured-cache-ttl=1ms")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedFeaturedIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    private long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM feed_event");
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM order_refund");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM category WHERE name NOT IN ('秒杀券', '软件', '实物')");
        jdbcTemplate.update("DELETE FROM file_object");
        clear(redis.keys("feed:*"));
        clear(redis.keys("cache:*"));

        categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        // 让 1ms 的运营位缓存过期
        Thread.sleep(5);
    }

    @Test
    void activeSeckillAppears() throws Exception {
        long productId = product("ON_SALE");
        activity(productId, "ACTIVE", -1, 1, 10, 2);

        mockMvc.perform(get("/api/feed/featured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(productId))
                .andExpect(jsonPath("$[0].seckillPrice").value(9.9))
                .andExpect(jsonPath("$[0].remaining").value(8))
                .andExpect(jsonPath("$[0].name").value("秒杀商品" + (SEQ.get())));
    }

    @Test
    void endedDraftOffShelfAndSoldOutAreExcluded() throws Exception {
        long offShelf = product("OFF_SHELF");
        activity(offShelf, "ACTIVE", -1, 1, 10, 0);

        long ended = product("ON_SALE");
        activity(ended, "ACTIVE", -3, -2, 10, 0);

        long draft = product("ON_SALE");
        activity(draft, "DRAFT", -1, 1, 10, 0);

        long soldOut = product("ON_SALE");
        activity(soldOut, "ACTIVE", -1, 1, 5, 5);

        mockMvc.perform(get("/api/feed/featured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private long product(String status) {
        String name = "秒杀商品" + SEQ.incrementAndGet();
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status,
                     created_at, updated_at)
                VALUES (?, ?, '秒杀测试', 'PHYSICAL', 19.90, 10, ?, NOW(3), NOW(3))
                """, categoryId, name, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, name);
    }

    private void activity(long productId, String status, int startHours, int endHours,
                          int stock, int sold) {
        jdbcTemplate.update("""
                INSERT INTO seckill_activity
                    (product_id, seckill_price, stock, sold_count, per_user_limit,
                     start_at, end_at, status)
                VALUES (?, 9.90, ?, ?, 1,
                        DATE_ADD(NOW(3), INTERVAL ? HOUR),
                        DATE_ADD(NOW(3), INTERVAL ? HOUR), ?)
                """, productId, stock, sold, startHours, endHours, status);
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
