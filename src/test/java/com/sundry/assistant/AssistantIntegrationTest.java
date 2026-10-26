package com.sundry.assistant;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 助手 L0：规则导航、角色裁剪、审计、限流。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantIntegrationTest {

    private static final String USER_PHONE = "13800138005";
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

    @MockitoBean
    private AssistantClient assistantClient;

    @BeforeEach
    void setUp() {
        reset(assistantClient);
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
        clear(redis.keys("assistant:session:*"));
        clear(redis.keys("nl2sql:session:*"));
        smsSender.clear();
    }

    @Test
    void userNavigatesToOwnOrdersAndAudits() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的订单在哪\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("navigate"))
                .andExpect(jsonPath("$.route").value("orders"))
                .andExpect(jsonPath("$.routeLabel").value("我的订单"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT source FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("RULE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("navigate");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT route FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("orders");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("OK");
    }

    @Test
    void adminRoutesAreNotVisibleToNormalUsers() throws Exception {
        Session user = register(USER_PHONE);
        Session admin = register(ADMIN_PHONE);

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"秒杀活动后台\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("clarify"))
                .andExpect(jsonPath("$.route").doesNotExist());

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"秒杀活动后台\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("navigate"))
                .andExpect(jsonPath("$.route").value("admin-seckill"));
    }

    @Test
    void auditMasksPhoneNumbers() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的订单 13800138000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("navigate"));

        String logged = jdbcTemplate.queryForObject(
                "SELECT message FROM ai_chat_log WHERE user_id = ?", String.class, user.id());
        assertThat(logged).contains("138****8000").doesNotContain("13800138000");
    }

    @Test
    void anonymousAndOversizedMessagesAreRejected() throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的订单在哪\"}"))
                .andExpect(status().isUnauthorized());

        Session user = register(USER_PHONE);
        String longMessage = "订".repeat(501);
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + longMessage + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modelNavigateCallIsValidatedAndExecuted() throws Exception {
        Session user = register(USER_PHONE);
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(new AssistantLlmResponse.ToolCall(
                        "call_1", "navigate", "{\"route\":\"orders\"}")),
                "", "deepseek", "assistant_v1"));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("navigate"))
                .andExpect(jsonPath("$.route").value("orders"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT source FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("LLM");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tool_name FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("navigate");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("OK");
    }

    @Test
    void modelCannotNavigateToAdminRouteAsUser() throws Exception {
        Session user = register(USER_PHONE);
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(new AssistantLlmResponse.ToolCall(
                        "call_1", "navigate", "{\"route\":\"admin-seckill\"}")),
                "", "deepseek", "assistant_v1"));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("clarify"))
                .andExpect(jsonPath("$.route").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("FORBIDDEN_ROUTE");
    }

    @Test
    void multipleToolCallsAndUnknownToolsAreRejected() throws Exception {
        Session user = register(USER_PHONE);
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(
                        new AssistantLlmResponse.ToolCall("c1", "navigate", "{\"route\":\"orders\"}"),
                        new AssistantLlmResponse.ToolCall("c2", "navigate", "{\"route\":\"cart\"}")),
                "", "deepseek", "assistant_v1"));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(jsonPath("$.action").value("clarify"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("MULTI_TOOL");

        reset(assistantClient);
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(new AssistantLlmResponse.ToolCall("c1", "drop_table", "{}")),
                "", "deepseek", "assistant_v1"));
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(jsonPath("$.action").value("clarify"));
    }

    @Test
    void modelTextAnswerAndUnavailableAreHandled() throws Exception {
        Session user = register(USER_PHONE);
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(), "可以在订单页查看物流。", "deepseek", "assistant_v1"));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(jsonPath("$.action").value("answer"))
                .andExpect(jsonPath("$.reply").value("可以在订单页查看物流。"));

        reset(assistantClient);
        when(assistantClient.chat(any(), anyString()))
                .thenThrow(new AssistantException("AI_UNAVAILABLE"));
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(jsonPath("$.action").value("clarify"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_chat_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user.id()))
                .isEqualTo("DEGRADED");
    }

    @Test
    void orderToolReturnsOnlyOwnOrders() throws Exception {
        Session user = register(USER_PHONE);
        order(user.id(), "TEST-ORDER-001", "SHIPPED");
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(new AssistantLlmResponse.ToolCall("c1", "list_my_orders", "{}")),
                "", "deepseek", "assistant_v1"));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("tool"))
                .andExpect(jsonPath("$.reply",
                        org.hamcrest.Matchers.containsString("TEST-ORDER-001")))
                .andExpect(jsonPath("$.reply",
                        org.hamcrest.Matchers.containsString("已发货")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("tool");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tool_name FROM ai_chat_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("list_my_orders");
    }

    @Test
    void productStatusToolIsAdminOnly() throws Exception {
        Session user = register(USER_PHONE);
        Session admin = register(ADMIN_PHONE);
        long productId = product("ON_SALE");
        String args = "{\"productId\":" + productId + "}";
        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(new AssistantLlmResponse.ToolCall("c1", "get_product_status", args)),
                "", "deepseek", "assistant_v1"));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("tool"))
                .andExpect(jsonPath("$.reply",
                        org.hamcrest.Matchers.containsString("助手测试商品")))
                .andExpect(jsonPath("$.reply",
                        org.hamcrest.Matchers.containsString("在售")));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我的包裹到哪了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("clarify"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM ai_chat_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user.id()))
                .isEqualTo("FORBIDDEN_TOOL");
    }

    @Test
    void eleventhRequestInAMinuteIsRateLimited() throws Exception {
        Session user = register(USER_PHONE);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/assistant/chat")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void modelReceivesPreviousTurnsInSameSession() throws Exception {
        Session user = register(USER_PHONE);
        when(assistantClient.chat(any(), anyString()))
                .thenReturn(new AssistantLlmResponse(
                        List.of(), "第一条回答", "deepseek", "assistant_v1"))
                .thenReturn(new AssistantLlmResponse(
                        List.of(), "第二条回答", "deepseek", "assistant_v1"));
        chat(user, "{\"sessionId\":\"session-a\",\"message\":\"你好\"}");
        chat(user, "{\"sessionId\":\"session-a\",\"message\":\"继续聊\"}");

        ArgumentCaptor<AssistantLlmRequest> captor =
                ArgumentCaptor.forClass(AssistantLlmRequest.class);
        verify(assistantClient, times(2)).chat(captor.capture(), anyString());
        List<Map<String, String>> history = captor.getAllValues().get(1).history();
        assertThat(history).containsExactly(
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "assistant", "content", "第一条回答"));
    }

    @Test
    void ruleNavigationTurnIsRemembered() throws Exception {
        Session user = register(USER_PHONE);
        chat(user, "{\"sessionId\":\"session-b\",\"message\":\"我的订单在哪\"}");

        when(assistantClient.chat(any(), anyString())).thenReturn(new AssistantLlmResponse(
                List.of(), "好的", "deepseek", "assistant_v1"));
        chat(user, "{\"sessionId\":\"session-b\",\"message\":\"你好\"}");

        ArgumentCaptor<AssistantLlmRequest> captor =
                ArgumentCaptor.forClass(AssistantLlmRequest.class);
        verify(assistantClient).chat(captor.capture(), anyString());
        List<Map<String, String>> history = captor.getValue().history();
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("role", "user")
                .containsEntry("content", "我的订单在哪");
        assertThat(history.get(1).get("role")).isEqualTo("assistant");
        assertThat(history.get(1).get("content")).contains("我的订单");
    }

    @Test
    void degradedTurnIsNotRemembered() throws Exception {
        Session user = register(USER_PHONE);
        when(assistantClient.chat(any(), anyString()))
                .thenThrow(new AssistantException("AI_UNAVAILABLE"));
        chat(user, "{\"sessionId\":\"session-c\",\"message\":\"你好\"}");

        String key = "assistant:session:" + user.id() + ":session-c";
        assertThat(redis.opsForList().range(key, 0, -1)).isEmpty();
    }

    @Test
    void invalidSessionIdIsRejected() throws Exception {
        Session user = register(USER_PHONE);
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"bad:key\",\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void chat(Session user, String body) throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void order(Long userId, String orderNo, String status) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, user_id, status, total_amount, has_physical, created_at)
                VALUES (?, ?, ?, 123.45, 1, NOW(3))
                """, orderNo, userId, status);
    }

    private long product(String status) {
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status,
                     created_at, updated_at)
                VALUES (?, '助手测试商品', '测试', 'PHYSICAL', 19.90, 10, ?, NOW(3), NOW(3))
                """, categoryId, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = '助手测试商品'", Long.class);
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
