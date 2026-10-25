package com.sundry.feed;

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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线评估：CTR 分母是曝光，NDCG 只统计有点击的 trace。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedMetricsIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138004";
    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

    private long catA;
    private long catB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM feed_event");
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
        smsSender.clear();

        catA = categoryId("实物");
        catB = categoryId("软件");
    }

    @Test
    void metricsUseExposureAsDenominatorAndSkipEmptyTracesForNdcg() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long p1 = product(catA, "指标商品1", "ON_SALE");
        long p2 = product(catA, "指标商品2", "ON_SALE");
        long p3 = product(catB, "指标商品3", "ON_SALE");
        long p4 = product(catB, "指标商品4", "ON_SALE");
        product(catA, "指标商品-下架", "OFF_SHELF");

        for (int position = 1; position <= 4; position++) {
            long product = position == 1 ? p1 : position == 2 ? p2 : position == 3 ? p3 : p4;
            long category = position <= 2 ? catA : catB;
            event("trace-ai", "EXPOSURE", "AI", product, category, position, 0);
            event("trace-rule", "EXPOSURE", "RULE", product, category, position, 0);
            event("trace-ai-empty", "EXPOSURE", "AI", product, category, position, 0);
        }
        event("trace-ai", "CLICK", "AI", p1, catA, 1, 0);
        event("trace-ai", "CLICK", "AI", p3, catB, 3, 0);
        event("trace-rule", "CLICK", "RULE", p4, catB, 4, 0);

        mockMvc.perform(get("/api/admin/feed/metrics").param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(7))
                .andExpect(jsonPath("$.exposures").value(12))
                .andExpect(jsonPath("$.clicks").value(3))
                .andExpect(jsonPath("$.ctr").value(0.25))
                .andExpect(jsonPath("$.byAlgorithm[0].algorithm").value("AI"))
                .andExpect(jsonPath("$.byAlgorithm[0].exposures").value(8))
                .andExpect(jsonPath("$.byAlgorithm[0].clicks").value(2))
                .andExpect(jsonPath("$.byAlgorithm[0].ctr").value(0.25))
                .andExpect(jsonPath("$.byAlgorithm[1].algorithm").value("RULE"))
                .andExpect(jsonPath("$.byAlgorithm[1].exposures").value(4))
                .andExpect(jsonPath("$.byAlgorithm[1].clicks").value(1))
                .andExpect(jsonPath("$.byPosition[0].position").value(1))
                .andExpect(jsonPath("$.byPosition[0].exposures").value(3))
                .andExpect(jsonPath("$.byPosition[0].clicks").value(1))
                .andExpect(jsonPath("$.byPosition[0].ctr").value(0.3333))
                .andExpect(jsonPath("$.byPosition[1].ctr").value(0.0))
                .andExpect(jsonPath("$.ndcg.overall").value(closeTo(0.6752, 0.0001)))
                .andExpect(jsonPath("$.ndcg.ai").value(closeTo(0.9197, 0.0001)))
                .andExpect(jsonPath("$.ndcg.rule").value(closeTo(0.4307, 0.0001)))
                .andExpect(jsonPath("$.ndcg.tracesWithClicks").value(2))
                .andExpect(jsonPath("$.ndcg.k").value(12))
                .andExpect(jsonPath("$.coverage.exposedProducts").value(4))
                .andExpect(jsonPath("$.coverage.onSaleProducts").value(4))
                .andExpect(jsonPath("$.coverage.rate").value(1.0))
                .andExpect(jsonPath("$.diversity.avgCategoriesPerTrace").value(2.0))
                .andExpect(jsonPath("$.diversity.traces").value(3));
    }

    @Test
    void strategyBreakdownGroupsByBuildIdPrefix() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long p1 = product(catA, "策略商品1", "ON_SALE");

        eventWithBuild("t-pref-1", "EXPOSURE", "AI",
                "PREF:11111111-1111-1111-1111-111111111111", p1, catA, 1);
        eventWithBuild("t-pref-2", "EXPOSURE", "AI",
                "PREF:11111111-1111-1111-1111-111111111111", p1, catA, 2);
        eventWithBuild("t-pref-3", "EXPOSURE", "AI",
                "PREF:11111111-1111-1111-1111-111111111111", p1, catA, 3);
        eventWithBuild("t-pref-4", "CLICK", "AI",
                "PREF:11111111-1111-1111-1111-111111111111", p1, catA, 1);

        eventWithBuild("t-exp-1", "EXPOSURE", "AI",
                "EXPLORE:22222222-2222-2222-2222-222222222222", p1, catA, 1);
        eventWithBuild("t-exp-2", "EXPOSURE", "AI",
                "EXPLORE:22222222-2222-2222-2222-222222222222", p1, catA, 2);

        // 旧格式 buildId 不参与策略分组
        eventWithBuild("t-legacy", "EXPOSURE", "AI",
                "550e8400-e29b-41d4-a716-446655440000", p1, catA, 1);
        // RULE 事件即使带了变体 buildId 也不计入策略
        eventWithBuild("t-rule-pref", "EXPOSURE", "RULE",
                "PREF:11111111-1111-1111-1111-111111111111", p1, catA, 1);

        mockMvc.perform(get("/api/admin/feed/metrics").param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStrategy.length()").value(2))
                .andExpect(jsonPath("$.byStrategy[0].strategy").value("EXPLORE"))
                .andExpect(jsonPath("$.byStrategy[0].exposures").value(2))
                .andExpect(jsonPath("$.byStrategy[0].clicks").value(0))
                .andExpect(jsonPath("$.byStrategy[0].ctr").value(0.0))
                .andExpect(jsonPath("$.byStrategy[1].strategy").value("PREF"))
                .andExpect(jsonPath("$.byStrategy[1].exposures").value(3))
                .andExpect(jsonPath("$.byStrategy[1].clicks").value(1))
                .andExpect(jsonPath("$.byStrategy[1].ctr").value(0.3333));
    }

    @Test
    void windowFiltersOldEvents() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long p1 = product(catA, "窗口商品", "ON_SALE");
        event("trace-new", "EXPOSURE", "RULE", p1, catA, 1, 0);
        event("trace-old", "EXPOSURE", "RULE", p1, catA, 1, 8);

        mockMvc.perform(get("/api/admin/feed/metrics").param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposures").value(1));

        mockMvc.perform(get("/api/admin/feed/metrics").param("days", "30")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposures").value(2));
    }

    @Test
    void normalUserCannotReadMetrics() throws Exception {
        Session user = register(USER_PHONE);
        mockMvc.perform(get("/api/admin/feed/metrics")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotReadMetrics() throws Exception {
        mockMvc.perform(get("/api/admin/feed/metrics"))
                .andExpect(status().isUnauthorized());
    }

    private long product(long categoryId, String name, String status) {
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status, created_at, updated_at)
                VALUES (?, ?, '指标测试', 'PHYSICAL', 10.00, 10, ?, NOW(3), NOW(3))
                """, categoryId, name, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, name);
    }

    private void event(String traceId, String type, String algorithm, long productId,
                       long categoryId, int position, int daysAgo) {
        jdbcTemplate.update("""
                INSERT INTO feed_event
                    (trace_id, user_id, event_type, algorithm, build_id,
                     product_id, category_id, item_position, created_at)
                VALUES (?, 1, ?, ?, NULL, ?, ?, ?, ?)
                """, traceId, type, algorithm, productId, categoryId, position,
                Timestamp.valueOf(LocalDateTime.now().minusDays(daysAgo)));
    }

    private void eventWithBuild(String traceId, String type, String algorithm, String buildId,
                                long productId, long categoryId, int position) {
        jdbcTemplate.update("""
                INSERT INTO feed_event
                    (trace_id, user_id, event_type, algorithm, build_id,
                     product_id, category_id, item_position, created_at)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, NOW(3))
                """, traceId, type, algorithm, buildId, productId, categoryId, position);
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @SuppressWarnings("unchecked")
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
        Number id = JsonPath.read(body, "$.user.id");
        return new Session(JsonPath.read(body, "$.accessToken"), id.longValue());
    }

    private record Session(String token, Long id) {
    }
}
