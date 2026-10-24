package com.sundry.catalog;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品浏览三级缓存：命中、失效、穿透空值、版本号和并发。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductCacheIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
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
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM order_refund");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        clear(redis.keys("auth:*"));
        clear(redis.keys("cache:*"));
        cache.clearLocalCaches();
        smsSender.clear();
    }

    @Test
    void detailIsCachedUntilEvicted() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createProduct(admin.token(), "缓存商品");

        assertThat(publicName(productId)).isEqualTo("缓存商品");
        assertThat(redis.hasKey("cache:product:detail:" + productId)).isTrue();
        Long ttl = redis.getExpire("cache:product:detail:" + productId);
        assertThat(ttl).isNotNull().isPositive();

        // 绕过服务直接改库，命中缓存时仍返回旧值
        jdbcTemplate.update("UPDATE product SET name = ? WHERE id = ?", "数据库改名", productId);
        assertThat(publicName(productId)).isEqualTo("缓存商品");

        cache.evictDetail(productId);
        assertThat(publicName(productId)).isEqualTo("数据库改名");
    }

    @Test
    void adminUpdateRefreshesCardWithoutBumpingListVersion() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createProduct(admin.token(), "改名前");

        assertThat(publicName(productId)).isEqualTo("改名前");
        assertThat(listNames()).contains("改名前");
        String versionBefore = redis.opsForValue().get("cache:products:version");

        long categoryId = categoryId();
        mockMvc.perform(put("/api/admin/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"改名后","description":"测试","price":100.00,"stock":10,
                                 "categoryId":%d,"type":"PHYSICAL"}
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改名后"));

        // 普通编辑只刷新卡片，不升级列表版本
        assertThat(redis.opsForValue().get("cache:products:version")).isEqualTo(versionBefore);
        assertThat(publicName(productId)).isEqualTo("改名后");
        assertThat(listNames()).contains("改名后").doesNotContain("改名前");
    }

    @Test
    void listCachesIdsAndCardsSeparately() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createProduct(admin.token(), "拆开缓存");

        assertThat(listNames()).contains("拆开缓存");

        Set<String> idPageKeys = redis.keys("cache:product-ids:*");
        assertThat(idPageKeys).isNotNull().isNotEmpty();
        assertThat(redis.hasKey("cache:product:detail:" + productId)).isTrue();
    }

    @Test
    void categoryChangeBumpsListVersion() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createProduct(admin.token(), "换分类商品");
        assertThat(listNames()).contains("换分类商品");
        String versionBefore = redis.opsForValue().get("cache:products:version");

        long softwareCategoryId = categoryId("软件");
        mockMvc.perform(put("/api/admin/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"换分类商品","description":"测试","price":100.00,"stock":10,
                                 "categoryId":%d,"type":"PHYSICAL"}
                                """.formatted(softwareCategoryId)))
                .andExpect(status().isOk());

        String versionAfter = redis.opsForValue().get("cache:products:version");
        assertThat(versionAfter).isNotEqualTo(versionBefore);
        assertThat(listNames(softwareCategoryId)).contains("换分类商品");
    }

    @Test
    void missingProductIsCachedAsNull() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long missingId = 999999L;

        mockMvc.perform(get("/api/products/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        assertThat(redis.hasKey("cache:product:null:" + missingId)).isTrue();

        long categoryId = categoryId();
        jdbcTemplate.update("""
                INSERT INTO product (id, name, description, price, stock, category_id, type, status)
                VALUES (?, '后插入商品', '测试', 100.00, 10, ?, 'PHYSICAL', 'ON_SALE')
                """, missingId, categoryId);

        // 空值缓存仍然生效
        mockMvc.perform(get("/api/products/{id}", missingId))
                .andExpect(status().isNotFound());

        cache.evictDetail(missingId);
        mockMvc.perform(get("/api/products/{id}", missingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("后插入商品"));
    }

    @Test
    void listVersionChangesAfterProductCreated() throws Exception {
        Session admin = register(ADMIN_PHONE);
        createProduct(admin.token(), "第一件");
        assertThat(listNames()).contains("第一件");
        String versionBefore = redis.opsForValue().get("cache:products:version");

        createProduct(admin.token(), "第二件");

        assertThat(listNames()).contains("第一件", "第二件");
        String versionAfter = redis.opsForValue().get("cache:products:version");
        assertThat(versionAfter).isNotNull();
        assertThat(versionAfter).isNotEqualTo(versionBefore);
    }

    @Test
    void categoriesAreCached() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk());
        assertThat(redis.hasKey("cache:categories")).isTrue();
        Long ttl = redis.getExpire("cache:categories");
        assertThat(ttl).isNotNull().isPositive();
    }

    @Test
    void concurrentDetailRequestsAllSucceed() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createProduct(admin.token(), "并发详情");

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    MvcResult result = mockMvc.perform(get("/api/products/{id}", productId))
                            .andReturn();
                    if (result.getResponse().getStatus() == 200) {
                        success.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(threads);
    }

    private String publicName(long productId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.name");
    }

    private List<String> listNames() throws Exception {
        return listNames(null);
    }

    @SuppressWarnings("unchecked")
    private List<String> listNames(Long categoryId) throws Exception {
        var request = get("/api/products").param("page", "1").param("size", "50");
        if (categoryId != null) {
            request = request.param("categoryId", String.valueOf(categoryId));
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.items[*].name");
    }

    private long createProduct(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":100.00,"stock":10,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(name, categoryId())))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private long categoryId() {
        return categoryId("实物");
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
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Session(JsonPath.read(body, "$.accessToken"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private record Session(String token) {
    }
}
