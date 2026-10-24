package com.sundry.feed;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.catalog.ProductCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首页 Feed：匿名分页、分类偏好、已购排除、缺货过滤和浏览去重。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

    @Autowired
    private ProductCacheService cache;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_behavior");
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM order_refund");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM category WHERE name NOT IN ('秒杀券', '软件', '实物')");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        clear(redis.keys("auth:*"));
        clear(redis.keys("cache:*"));
        clear(redis.keys("feed:*"));
        clear(redis.keys("behavior:*"));
        cache.clearLocalCaches();
        smsSender.clear();
    }

    @Test
    void anonymousFeedPagesWithoutDuplicatesAndMixesCategories() throws Exception {
        String admin = register(ADMIN_PHONE).token();
        long software = categoryId("软件");
        long physical = categoryId("实物");
        for (int i = 1; i <= 10; i++) {
            createProduct(admin, software, "软件商品" + i, 5);
            createProduct(admin, physical, "实物商品" + i, 5);
        }

        MvcResult first = mockMvc.perform(get("/api/feed").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        List<Long> firstIds = ids(first);
        List<Long> firstCategories = categories(first);
        assertThat(new HashSet<>(firstCategories)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(redis.hasKey("feed:home:anon")).isTrue();

        long cursor = ((Number) JsonPath.read(body(first), "$.nextCursor")).longValue();
        MvcResult second = mockMvc.perform(get("/api/feed")
                        .param("size", "10")
                        .param("cursor", String.valueOf(cursor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn();
        List<Long> secondIds = ids(second);
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);

        Set<Long> all = new HashSet<>(firstIds);
        all.addAll(secondIds);
        assertThat(all).hasSize(20);
    }

    @Test
    void viewedCategoryGetsMoreWeight() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long software = categoryId("软件");
        long physical = categoryId("实物");
        List<Long> softwareIds = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            softwareIds.add(createProduct(admin.token(), software, "偏好软件" + i, 5));
            createProduct(admin.token(), physical, "普通实物" + i, 5);
        }

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/behaviors/view")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId":%d}
                                    """.formatted(softwareIds.get(i))))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_behavior WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(5);

        MvcResult feed = mockMvc.perform(get("/api/feed")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        List<Long> categories = categories(feed);
        long softwareCount = categories.stream().filter(id -> id == software).count();
        assertThat(softwareCount).isGreaterThanOrEqualTo(5);
    }

    @Test
    void purchasedProductsAreExcluded() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long software = categoryId("软件");
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ids.add(createProduct(admin.token(), software, "已购测试" + i, 5));
        }
        long purchasedId = ids.get(0);
        long orderId = createOrder(user.id(), purchasedId, "feed-purchase-1");

        MvcResult feed = mockMvc.perform(get("/api/feed")
                        .param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(feed)).doesNotContain(purchasedId);
        assertThat(orderId).isPositive();
    }

    @Test
    void outOfStockPhysicalProductsAreExcluded() throws Exception {
        String admin = register(ADMIN_PHONE).token();
        long physical = categoryId("实物");
        long normal = createProduct(admin, physical, "有货商品", 5);
        long soldOut = createProduct(admin, physical, "缺货商品", 0);

        MvcResult feed = mockMvc.perform(get("/api/feed").param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        List<Long> ids = ids(feed);
        assertThat(ids).contains(normal);
        assertThat(ids).doesNotContain(soldOut);
    }

    @Test
    void viewDeduplicatesAndRequiresLogin() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long physical = categoryId("实物");
        long productId = createProduct(admin.token(), physical, "去重商品", 5);

        mockMvc.perform(post("/api/behaviors/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d}
                                """.formatted(productId)))
                .andExpect(status().isUnauthorized());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/behaviors/view")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId":%d}
                                    """.formatted(productId)))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_behavior WHERE user_id = ? AND product_id = ?",
                Long.class, user.id(), productId)).isEqualTo(1);
    }

    private long createProduct(String token, long categoryId, String name, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":10.00,"stock":%d,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(name, stock, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private long createOrder(Long userId, Long productId, String orderNo) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, user_id, status, total_amount, has_physical)
                VALUES (?, ?, 'PAID', 10.00, 1)
                """, orderNo, userId);
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
        jdbcTemplate.update("""
                INSERT INTO order_item (order_id, product_id, product_name, product_type, price, quantity)
                VALUES (?, ?, '已购测试', 'PHYSICAL', 10.00, 1)
                """, orderId, productId);
        return orderId;
    }

    @SuppressWarnings("unchecked")
    private List<Long> ids(MvcResult result) throws Exception {
        List<Number> values = JsonPath.read(body(result), "$.items[*].id");
        List<Long> ids = new ArrayList<>();
        for (Number value : values) {
            ids.add(value.longValue());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<Long> categories(MvcResult result) throws Exception {
        List<Number> values = JsonPath.read(body(result), "$.items[*].categoryId");
        List<Long> ids = new ArrayList<>();
        for (Number value : values) {
            ids.add(value.longValue());
        }
        return ids;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private Session register(String phone) throws Exception {
        redis.delete("auth:sms:cooldown:" + phone);
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"REGISTER"}
                                """.formatted(phone)))
                .andExpect(status().isNoContent());
        String code = smsSender.code(SmsScene.REGISTER, phone);
        assertThat(code).isNotNull();
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(phone, code, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String body = body(result);
        Number id = JsonPath.read(body, "$.user.id");
        return new Session(JsonPath.read(body, "$.accessToken"), id.longValue());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private record Session(String token, Long id) {
    }
}
