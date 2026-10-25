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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品标签：trim、去重、上限、详情返回。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductTagIntegrationTest {

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
    }

    @Test
    void createsAndUpdatesTagsWithTrimAndDedupe() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long category = categoryId("实物");
        MvcResult created = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("标签商品", category, """
                                [" 办公 ", "效率", "办公"]
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("办公"))
                .andExpect(jsonPath("$.tags[1]").value("效率"))
                .andReturn();
        long id = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0]").value("办公"));

        mockMvc.perform(put("/api/admin/products/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("标签商品", category, """
                                ["新标签"]
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("新标签"));
    }

    @Test
    void rejectsTooManyTags() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long category = categoryId("实物");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("标签过多", category, """
                                ["1","2","3","4","5","6","7","8","9"]
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_TAGS_INVALID"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product", Long.class)).isZero();
    }

    @Test
    void rejectsTooLongTag() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long category = categoryId("实物");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("标签过长", category, """
                                ["这个标签明显超过二十个字符这个标签明显超过二十个字符"]
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_TAGS_INVALID"));
    }

    private String payload(String name, long categoryId, String tags) {
        return """
                {"name":"%s","description":"标签测试","price":10.00,"stock":5,
                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE","tags":%s}
                """.formatted(name, categoryId, tags);
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
        String body = body(result);
        Number id = JsonPath.read(body, "$.user.id");
        return new Session(JsonPath.read(body, "$.accessToken"), id.longValue());
    }

    private record Session(String token, Long id) {
    }
}
