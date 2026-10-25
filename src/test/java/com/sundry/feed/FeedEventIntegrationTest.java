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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 曝光/点击回流：鉴权、去重、分类补全、规则与 AI 标记。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedEventIntegrationTest {

    private static final String USER_PHONE = "13800138003";
    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

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
        clear(redis.keys("behavior:*"));
        smsSender.clear();
    }

    @Test
    void exposureAndClickAreStored() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long first = createProduct(category, "回流商品A");
        long second = createProduct(category, "回流商品B");

        postEvents(user.token(), body("trace-00000001", "build-1", "AI", """
                [{"type":"EXPOSURE","productId":%d,"position":1},
                 {"type":"EXPOSURE","productId":%d,"position":2},
                 {"type":"CLICK","productId":%d,"position":1}]
                """.formatted(first, second, first)))
                .andExpect(status().isNoContent());

        assertThat(count()).isEqualTo(3);

        Map<String, Object> click = jdbcTemplate.queryForMap("""
                SELECT user_id, category_id, algorithm, build_id, item_position
                FROM feed_event
                WHERE event_type = 'CLICK' AND product_id = ?
                """, first);
        assertThat(((Number) click.get("user_id")).longValue()).isEqualTo(user.id());
        assertThat(((Number) click.get("category_id")).longValue()).isEqualTo(category);
        assertThat(click.get("algorithm")).isEqualTo("AI");
        assertThat(click.get("build_id")).isEqualTo("build-1");
        assertThat(((Number) click.get("item_position")).intValue()).isEqualTo(1);
    }

    @Test
    void duplicateEventsAreIdempotent() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long product = createProduct(category, "去重商品");

        String body = body("trace-00000002", null, "RULE", """
                [{"type":"EXPOSURE","productId":%d,"position":1},
                 {"type":"EXPOSURE","productId":%d,"position":1}]
                """.formatted(product, product));

        postEvents(user.token(), body).andExpect(status().isNoContent());
        postEvents(user.token(), body).andExpect(status().isNoContent());

        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT build_id FROM feed_event", String.class)).isNull();
    }

    @Test
    void anonymousIsRejected() throws Exception {
        long category = categoryId("实物");
        long product = createProduct(category, "匿名商品");

        mockMvc.perform(post("/api/feed/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("trace-00000003", null, "RULE", """
                                [{"type":"EXPOSURE","productId":%d,"position":1}]
                                """.formatted(product))))
                .andExpect(status().isUnauthorized());

        assertThat(count()).isZero();
    }

    @Test
    void invalidEventTypeIsRejected() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long product = createProduct(category, "非法类型商品");

        postEvents(user.token(), body("trace-00000004", null, "RULE", """
                [{"type":"VIEW","productId":%d,"position":1}]
                """.formatted(product)))
                .andExpect(status().isBadRequest());

        assertThat(count()).isZero();
    }

    @Test
    void invalidPositionIsRejected() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long product = createProduct(category, "非法位置商品");

        postEvents(user.token(), body("trace-00000005", null, "RULE", """
                [{"type":"EXPOSURE","productId":%d,"position":0}]
                """.formatted(product)))
                .andExpect(status().isBadRequest());

        assertThat(count()).isZero();
    }

    @Test
    void batchOverLimitIsRejected() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long product = createProduct(category, "超量商品");

        StringBuilder events = new StringBuilder("[");
        for (int i = 1; i <= 101; i++) {
            if (i > 1) {
                events.append(",");
            }
            events.append("""
                    {"type":"EXPOSURE","productId":%d,"position":%d}
                    """.formatted(product, i));
        }
        events.append("]");

        postEvents(user.token(), body("trace-00000006", null, "RULE", events.toString()))
                .andExpect(status().isBadRequest());

        assertThat(count()).isZero();
    }

    @Test
    void unknownProductIsIgnored() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long product = createProduct(category, "有效商品");

        postEvents(user.token(), body("trace-00000007", null, "RULE", """
                [{"type":"EXPOSURE","productId":%d,"position":1},
                 {"type":"EXPOSURE","productId":999999,"position":2}]
                """.formatted(product)))
                .andExpect(status().isNoContent());

        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_id FROM feed_event", Long.class)).isEqualTo(product);
    }

    @Test
    void userIdCannotBeSpoofed() throws Exception {
        Session user = register(USER_PHONE);
        long category = categoryId("实物");
        long product = createProduct(category, "越权商品");

        String payload = """
                {"userId":999,"traceId":"trace-00000008","algorithm":"RULE",
                 "events":[{"type":"EXPOSURE","productId":%d,"position":1}]}
                """.formatted(product);

        postEvents(user.token(), payload).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM feed_event", Long.class)).isEqualTo(user.id());
    }

    private org.springframework.test.web.servlet.ResultActions postEvents(String token, String body)
            throws Exception {
        return mockMvc.perform(post("/api/feed/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String traceId, String buildId, String algorithm, String events) {
        String build = buildId == null ? "null" : "\"" + buildId + "\"";
        return """
                {"traceId":"%s","buildId":%s,"algorithm":"%s","events":%s}
                """.formatted(traceId, build, algorithm, events);
    }

    private long count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feed_event", Long.class);
    }

    private long createProduct(long categoryId, String name) {
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status,
                     created_at, updated_at)
                VALUES (?, ?, '测试', 'PHYSICAL', 10.00, 10, 'ON_SALE', NOW(3), NOW(3))
                """, categoryId, name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, name);
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
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
