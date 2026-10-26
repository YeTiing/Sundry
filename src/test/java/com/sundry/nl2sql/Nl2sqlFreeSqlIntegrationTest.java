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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自由 SQL 沙箱：超管可用、危险 SQL 拒绝、独立只读数据源执行。
 */
@SpringBootTest(properties = {
        "app.nl2sql.enabled=true",
        "app.nl2sql.free-sql-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Nl2sqlFreeSqlIntegrationTest {

    private static final String SUPER_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138012";
    private static final String PASSWORD = "secret123";

    @DynamicPropertySource
    static void freeDatasource(DynamicPropertyRegistry registry) {
        registry.add("app.nl2sql.free-datasource.url", () ->
                "jdbc:mysql://127.0.0.1:3306/sundry_test?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("app.nl2sql.free-datasource.username", () -> "root");
        registry.add("app.nl2sql.free-datasource.password",
                () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    }

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
        jdbcTemplate.update("DELETE FROM nl2sql_log");
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
        clear(redis.keys("nl2sql:rate:*"));
        clear(redis.keys("nl2sql:session:*"));
        smsSender.clear();
    }

    @Test
    void superAdminRunsSafeQueryAndAddsLimit() throws Exception {
        Session admin = register(SUPER_PHONE);
        seedOrderData(admin.id());

        MvcResult result = mockMvc.perform(post("/api/admin/nl2sql/free-query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":" + quote("SELECT o.status, COUNT(*) AS cnt, "
                                + "SUM(oi.quantity) AS items FROM orders o "
                                + "JOIN order_item oi ON oi.order_id = o.id "
                                + "GROUP BY o.status") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].key").value("status"))
                .andExpect(jsonPath("$.columns[1].key").value("cnt"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("PAID"))
                .andExpect(jsonPath("$.rows[0].cnt").value(1))
                .andExpect(jsonPath("$.rows[0].items").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(body, "$.sql")).contains("LIMIT 1000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id())).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sql_text FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id())).contains("LIMIT 1000");
    }

    @Test
    void normalUserCannotUseFreeSql() throws Exception {
        Session user = register(USER_PHONE);
        mockMvc.perform(post("/api/admin/nl2sql/free-query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT status FROM orders\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_log", Integer.class)).isZero();
    }

    @Test
    void dangerousSqlIsRejectedAndDataStaysIntact() throws Exception {
        Session admin = register(SUPER_PHONE);
        seedOrderData(admin.id());

        List<String> attacks = List.of(
                "SELECT * FROM orders",
                "SELECT receiver_phone FROM orders",
                "SELECT id FROM app_user",
                "SELECT table_name FROM information_schema.tables",
                "SELECT SLEEP(5) FROM orders",
                "SELECT status FROM orders; DELETE FROM orders",
                "SELECT status FROM orders UNION SELECT status FROM orders",
                "SELECT status FROM orders LIMIT 100000");

        for (String sql : attacks) {
            mockMvc.perform(post("/api/admin/nl2sql/free-query")
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\":" + quote(sql) + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("NL2SQL_FREE_SQL_REJECTED"));
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_log WHERE user_id = ? AND status = 'REJECTED'",
                Integer.class, admin.id())).isEqualTo(attacks.size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nl2sql_log WHERE user_id = ? AND sql_text IS NOT NULL",
                Integer.class, admin.id())).isZero();
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private void seedOrderData(Long userId) {
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status,
                     created_at, updated_at)
                VALUES (?, '自由 SQL 商品', '测试', 'PHYSICAL', 19.90, 10, 'ON_SALE', NOW(3), NOW(3))
                """, categoryId);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = '自由 SQL 商品'", Long.class);

        jdbcTemplate.update("""
                INSERT INTO orders
                    (order_no, user_id, status, total_amount, has_physical, paid_at, created_at)
                VALUES ('FREE-ORDER-001', ?, 'PAID', 20.00, 1,
                        NOW(3) - INTERVAL 1 DAY, NOW(3) - INTERVAL 1 DAY)
                """, userId);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = 'FREE-ORDER-001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_item
                    (order_id, product_id, product_name, product_type, price, quantity)
                VALUES (?, ?, '自由 SQL 商品', 'PHYSICAL', 10.00, 2)
                """, orderId, productId);
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
