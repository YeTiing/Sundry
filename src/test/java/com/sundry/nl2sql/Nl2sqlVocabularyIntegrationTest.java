package com.sundry.nl2sql;

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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语义层 S1：结构加载、启动校验、词汇表接口不泄露 SQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Nl2sqlVocabularyIntegrationTest {

    private static final String USER_PHONE = "13800138006";
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
    private SemanticLayerProvider provider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_chat_log");
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
        smsSender.clear();
    }

    @Test
    void semanticLayerIsLoadedWithExpectedShape() {
        SemanticLayer layer = provider.layer();

        assertThat(layer.metrics()).hasSize(10);
        assertThat(layer.dimensions()).hasSize(7);
        assertThat(layer.filters()).hasSize(5);
        assertThat(layer.metrics().get("gmv").expr()).contains("SUM(");
        assertThat(layer.metrics().get("refund_amount").conditionalJoin())
                .isEqualTo("refund_summary");
        assertThat(layer.metrics().get("refund_order_count").conditionalJoin())
                .isEqualTo("refund_summary");
        assertThat(layer.dimensions().get("buyer").column()).contains("****");
        assertThat(layer.limits().maxMetrics()).isEqualTo(3);
    }

    @Test
    void adminReadsVocabularyWithoutSqlLeak() throws Exception {
        Session admin = register(ADMIN_PHONE);

        MvcResult result = mockMvc.perform(get("/api/admin/nl2sql/vocabulary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(10))
                .andExpect(jsonPath("$.dimensions.length()").value(7))
                .andExpect(jsonPath("$.filters.length()").value(5))
                .andExpect(jsonPath("$.limits.maxMetrics").value(3))
                .andExpect(jsonPath("$.limits.maxRows").value(1000))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> metricKeys = JsonPath.read(body, "$.metrics[*].key");
        assertThat(metricKeys).contains("gmv", "refund_rate", "seckill_order_count",
                "refund_order_count", "avg_item_price", "seckill_gmv");
        assertThat(JsonPath.<List<String>>read(body, "$.dimensions[*].key"))
                .contains("status", "buyer");
        assertThat(JsonPath.<List<String>>read(body, "$.filters[*].key"))
                .contains("product_type");
        assertThat(body)
                .doesNotContain("SUM(")
                .doesNotContain("orders")
                .doesNotContain("order_refund")
                .doesNotContain("JOIN")
                .doesNotContain(" o.")
                .doesNotContain(" oi.");
    }

    @Test
    void normalUserAndAnonymousCannotRead() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(get("/api/admin/nl2sql/vocabulary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/nl2sql/vocabulary"))
                .andExpect(status().isUnauthorized());
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
