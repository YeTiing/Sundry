package com.sundry.assistant;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.nl2sql.Nl2sqlClient;
import com.sundry.nl2sql.dto.Nl2sqlPlanResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4 L3：管理员通过助手问经营数据，完整走 NL2SQL 链路；普通用户拿不到该工具。
 */
@SpringBootTest(properties = "app.nl2sql.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantQuerySalesIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138008";
    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

    @MockitoBean
    private AssistantClient assistantClient;

    @MockitoBean
    private Nl2sqlClient nl2sqlClient;

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
        smsSender.clear();
        reset(assistantClient, nl2sqlClient);
    }

    @Test
    void adminCanAskSalesThroughAssistant() throws Exception {
        Session admin = register(ADMIN_PHONE);
        seedOrderData(admin.id());
        stubTools();

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"最近经营情况怎么样\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("tool"))
                .andExpect(jsonPath("$.reply", containsString("实物")))
                .andExpect(jsonPath("$.reply", containsString("20")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM ai_chat_log WHERE user_id = ?", String.class, admin.id()))
                .isEqualTo("tool");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tool_name FROM ai_chat_log WHERE user_id = ?", String.class, admin.id()))
                .isEqualTo("query_sales");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM nl2sql_log WHERE user_id = ?", String.class, admin.id()))
                .isEqualTo("SUCCESS");
    }

    @Test
    void normalUserCannotGetSalesTool() throws Exception {
        Session user = register(USER_PHONE);
        stubTools();

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"最近经营情况怎么样\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("clarify"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("FORBIDDEN_TOOL");
    }

    private void stubTools() {
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(new AssistantLlmResponse.ToolCall(
                        "call_1", "query_sales", "{\"question\":\"最近30天各分类销售额\"}")),
                "", "deepseek", "assistant_v1"));
        when(nl2sqlClient.plan(any(), anyString())).thenReturn(new Nl2sqlPlanResponse(
                new Nl2sqlPlanResponse.Plan(
                        List.of("gmv"), List.of("category"), List.of(),
                        new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10),
                false, null, "deepseek", "nl2sql_plan_v1"));
    }

    private void seedOrderData(Long userId) {
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status,
                     created_at, updated_at)
                VALUES (?, '助手分析商品', '测试', 'PHYSICAL', 19.90, 10, 'ON_SALE', NOW(3), NOW(3))
                """, categoryId);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = '助手分析商品'", Long.class);

        jdbcTemplate.update("""
                INSERT INTO orders
                    (order_no, user_id, status, total_amount, has_physical, paid_at, created_at)
                VALUES ('NL-ASSIST-001', ?, 'PAID', 20.00, 1,
                        NOW(3) - INTERVAL 1 DAY, NOW(3) - INTERVAL 1 DAY)
                """, userId);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = 'NL-ASSIST-001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_item
                    (order_id, product_id, product_name, product_type, price, quantity)
                VALUES (?, ?, '助手分析商品', 'PHYSICAL', 10.00, 2)
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
