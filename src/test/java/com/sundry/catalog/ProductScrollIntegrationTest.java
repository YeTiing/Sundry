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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品列表游标分页：无重复、无遗漏、分类过滤和边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductScrollIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM category WHERE name NOT IN ('秒杀券', '软件', '实物')");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        clear(redis.keys("auth:*"));
        clear(redis.keys("cache:*"));
        cache.clearLocalCaches();
        smsSender.clear();
    }

    @Test
    void scrollPagesHaveNoDuplicateOrGap() throws Exception {
        String token = register(ADMIN_PHONE);
        long physical = categoryId("实物");
        for (int i = 1; i <= 25; i++) {
            createProduct(token, physical, "滚动商品" + String.format("%02d", i));
        }

        MvcResult first = mockMvc.perform(get("/api/products/scroll")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        List<Long> firstIds = ids(first);
        long cursor = ((Number) JsonPath.read(body(first), "$.nextCursor")).longValue();
        assertThat(cursor).isEqualTo(firstIds.get(firstIds.size() - 1));

        MvcResult second = mockMvc.perform(get("/api/products/scroll")
                        .param("size", "10")
                        .param("cursor", String.valueOf(cursor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        List<Long> secondIds = ids(second);
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
        long secondCursor = ((Number) JsonPath.read(body(second), "$.nextCursor")).longValue();

        MvcResult third = mockMvc.perform(get("/api/products/scroll")
                        .param("size", "10")
                        .param("cursor", String.valueOf(secondCursor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn();
        List<Long> thirdIds = ids(third);
        assertThat(thirdIds).doesNotContainAnyElementsOf(firstIds);
        assertThat(thirdIds).doesNotContainAnyElementsOf(secondIds);

        Set<Long> all = new HashSet<>();
        all.addAll(firstIds);
        all.addAll(secondIds);
        all.addAll(thirdIds);
        assertThat(all).hasSize(25);
    }

    @Test
    void scrollRespectsCategoryFilter() throws Exception {
        String token = register(ADMIN_PHONE);
        long physical = categoryId("实物");
        long digitalAccessory = categoryId("软件");
        createProduct(token, physical, "普通商品");
        for (int i = 1; i <= 3; i++) {
            createProduct(token, digitalAccessory, "配件商品" + i);
        }

        MvcResult filtered = mockMvc.perform(get("/api/products/scroll")
                        .param("categoryId", String.valueOf(digitalAccessory))
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn();
        assertThat(ids(filtered)).hasSize(3);
    }

    @Test
    void emptyCategoryReturnsEmptyPage() throws Exception {
        register(ADMIN_PHONE);
        long design = categoryId("软件");
        mockMvc.perform(get("/api/products/scroll")
                        .param("categoryId", String.valueOf(design)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void invalidCursorIsRejected() throws Exception {
        register(ADMIN_PHONE);
        mockMvc.perform(get("/api/products/scroll").param("cursor", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oldPageEndpointStillWorks() throws Exception {
        String token = register(ADMIN_PHONE);
        long physical = categoryId("实物");
        for (int i = 1; i <= 5; i++) {
            createProduct(token, physical, "分页商品" + i);
        }

        mockMvc.perform(get("/api/products").param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(3));
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

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long createProduct(String token, long categoryId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":10.00,"stock":5,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(name, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private String register(String phone) throws Exception {
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
        return JsonPath.read(body(result), "$.accessToken");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
