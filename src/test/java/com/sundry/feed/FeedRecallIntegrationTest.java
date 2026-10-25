package com.sundry.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多路召回：分类、协同、热销、新品，合并去重。
 * min-candidates 设为 1，避免兜底把全部商品加进来掩盖路由效果。
 */
@SpringBootTest(properties = "app.feed.recall-min-candidates=1")
@ActiveProfiles("test")
class FeedRecallIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FeedRecallService recallService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM feed_event");
        jdbcTemplate.update("DELETE FROM user_behavior");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM app_user");
        for (String name : List.of("电子书", "在线课程", "音乐音效", "摄影素材")) {
            ensureCategory(name);
        }
    }

    private void ensureCategory(String name) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE name = ?", Long.class, name);
        if (count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO category (name, sort_order, status) VALUES (?, 0, 1)", name);
        }
    }

    @Test
    void recallIncludesCategoryCoPurchaseHotAndNew() {
        long physical = categoryId("实物");
        long ebook = categoryId("电子书");
        long course = categoryId("在线课程");
        long music = categoryId("音乐音效");
        long photo = categoryId("摄影素材");

        long target = user("13600000091");
        long other = user("13600000092");
        long buyer = user("13600000093");

        long categoryProduct = product(physical, "召回-分类", LocalDateTime.now().minusDays(30));
        long boughtProduct = product(ebook, "召回-已购", LocalDateTime.now().minusDays(30));
        long coProduct = product(photo, "召回-协同", LocalDateTime.now().minusDays(30));
        long hotProduct = product(music, "召回-热销", LocalDateTime.now().minusDays(30));
        long newProduct = product(course, "召回-新品", LocalDateTime.now());

        view(target, categoryProduct, physical);
        order(target, "REC-U0", List.of(new Item(boughtProduct, 1)));
        order(other, "REC-U1", List.of(new Item(boughtProduct, 1), new Item(coProduct, 1)));
        order(buyer, "REC-U2", List.of(new Item(hotProduct, 5)));

        FeedRecallService.RecallResult result = recallService.recall(target);

        assertThat(result.ids()).contains(categoryProduct, coProduct, hotProduct, newProduct);
        assertThat(result.ids()).doesNotHaveDuplicates();
        assertThat(result.category()).isPositive();
        assertThat(result.coPurchase()).isPositive();
        assertThat(result.hot()).isPositive();
        assertThat(result.newCount()).isPositive();
    }

    @Test
    void anonymousRecallOnlyUsesHotAndNew() {
        long music = categoryId("音乐音效");
        long course = categoryId("在线课程");
        long buyer = user("13600000094");

        long hotProduct = product(music, "匿名-热销", LocalDateTime.now().minusDays(30));
        long newProduct = product(course, "匿名-新品", LocalDateTime.now());
        order(buyer, "REC-ANON", List.of(new Item(hotProduct, 3)));

        FeedRecallService.RecallResult result = recallService.recall(null);

        assertThat(result.ids()).contains(hotProduct, newProduct);
        assertThat(result.category()).isZero();
        assertThat(result.coPurchase()).isZero();
        assertThat(result.newCount()).isPositive();
    }

    private long user(String phone) {
        jdbcTemplate.update("""
                INSERT INTO app_user (phone, nickname, password_hash, status, role)
                VALUES (?, '召回测试', 'x', 1, 'USER')
                """, phone);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE phone = ?", Long.class, phone);
    }

    private long product(long categoryId, String name, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status, created_at, updated_at)
                VALUES (?, ?, '召回测试', 'PHYSICAL', 10.00, 10, 'ON_SALE', ?, NOW(3))
                """, categoryId, name, Timestamp.valueOf(createdAt));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, name);
    }

    private void view(long userId, long productId, long categoryId) {
        jdbcTemplate.update("""
                INSERT INTO user_behavior
                    (event_id, user_id, event_type, product_id, category_id, created_at)
                VALUES (?, ?, 'VIEW', ?, ?, NOW(3))
                """, "rec-view-" + userId + "-" + productId, userId, productId, categoryId);
    }

    private void order(long userId, String orderNo, List<Item> items) {
        double total = items.stream().mapToDouble(item -> 10.00 * item.quantity()).sum();
        jdbcTemplate.update("""
                INSERT INTO orders
                    (order_no, order_source, user_id, status, total_amount, has_physical, created_at)
                VALUES (?, 'NORMAL', ?, 'COMPLETED', ?, 1, NOW(3))
                """, orderNo, userId, total);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
        for (Item item : items) {
            jdbcTemplate.update("""
                    INSERT INTO order_item
                        (order_id, product_id, product_name, product_type, price, quantity)
                    VALUES (?, ?, '召回商品', 'PHYSICAL', 10.00, ?)
                    """, orderId, item.productId(), item.quantity());
        }
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private record Item(long productId, int quantity) {
    }
}
