package com.sundry.nl2sql;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.nl2sql.dto.Nl2sqlPlanRequest;
import com.sundry.nl2sql.dto.Nl2sqlPlanResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.nl2sql.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Nl2sqlQueryIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138007";
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
        clear(redis.keys("assistant:session:*"));
        clear(redis.keys("nl2sql:session:*"));
        clear(redis.keys("nl2sql:rate:*"));
        smsSender.clear();
        reset(nl2sqlClient);
    }

    @Test
    void adminCanQueryGmvByCategory() throws Exception {
        Session admin = register(ADMIN_PHONE);
        seedOrderData(admin.id());
        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of("category"), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));

        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"最近30天各分类销售额\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.columns[0].key").value("category"))
                .andExpect(jsonPath("$.columns[1].key").value("gmv"))
                .andExpect(jsonPath("$.rows[0].category").value("实物"))
                .andExpect(jsonPath("$.rows[0].gmv").value(20.0))
                .andExpect(jsonPath("$.answer").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id())).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_count FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, admin.id())).isEqualTo(1);
        String sql = jdbcTemplate.queryForObject(
                "SELECT sql_text FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id());
        assertThat(sql).contains("SELECT").contains("?").doesNotContain("20.00");
    }

    @Test
    void buyerDimensionIsMaskedAndNewMetricsWork() throws Exception {
        Session admin = register(ADMIN_PHONE);
        seedOrderData(admin.id());
        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("gmv", "avg_item_price", "refund_order_count"),
                List.of("buyer"), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));

        MvcResult result = mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"最近30天各买家的销售额、平均单价和退款订单数\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].key").value("buyer"))
                .andExpect(jsonPath("$.columns[1].key").value("gmv"))
                .andExpect(jsonPath("$.columns[2].key").value("avg_item_price"))
                .andExpect(jsonPath("$.columns[3].key").value("refund_order_count"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("139****0000").doesNotContain("13900000000");

        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of("status"),
                List.of(new Nl2sqlPlanResponse.Plan.Filter("product_type", "PHYSICAL"),
                        new Nl2sqlPlanResponse.Plan.Filter("status", "PAID")),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));

        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"最近30天已支付的实物订单销售额\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].status").value("PAID"))
                .andExpect(jsonPath("$.rows[0].gmv").value(20.0));
    }

    @Test
    void adminCanReadAuditLogsWithStatusFilter() throws Exception {
        Session admin = register(ADMIN_PHONE);
        jdbcTemplate.update("""
                INSERT INTO nl2sql_log
                    (user_id, role, question, row_count, latency_ms, status, trace_id)
                VALUES (?, 'SUPER_ADMIN', '最近30天销售额', 3, 120, 'SUCCESS', 'trace-1')
                """, admin.id());
        jdbcTemplate.update("""
                INSERT INTO nl2sql_log
                    (user_id, role, question, row_count, latency_ms, status,
                     reject_reason, trace_id)
                VALUES (?, 'SUPER_ADMIN', '用户性别分布', 0, 80, 'REJECTED',
                        'UNSUPPORTED_METRIC: 不支持的指标', 'trace-2')
                """, admin.id());

        mockMvc.perform(get("/api/admin/nl2sql/logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].question").value("用户性别分布"))
                .andExpect(jsonPath("$.items[1].status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/nl2sql/logs")
                        .param("status", "SUCCESS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].question").value("最近30天销售额"));

        Session user = register(USER_PHONE);
        mockMvc.perform(get("/api/admin/nl2sql/logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    void normalUserCannotQuery() throws Exception {
        Session user = register(USER_PHONE);
        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"销售额\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unsupportedAndInvalidPlansAreRejected() throws Exception {
        Session admin = register(ADMIN_PHONE);
        when(nl2sqlClient.plan(any(), anyString())).thenReturn(new Nl2sqlPlanResponse(
                null, true, "词汇表没有这个维度", "deepseek", "nl2sql_plan_v1"));

        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"用户性别分布\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id())).isEqualTo("UNSUPPORTED");

        reset(nl2sqlClient);
        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("password_hash"), List.of(), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));

        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"查密码\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id())).isEqualTo("REJECTED");
    }

    @Test
    void plannerReceivesPreviousTurnsInSameSession() throws Exception {
        Session admin = register(ADMIN_PHONE);
        seedOrderData(admin.id());
        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of("category"), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));

        query(admin, "{\"question\":\"最近30天各分类销售额\",\"sessionId\":\"nl-session-a\"}");
        query(admin, "{\"question\":\"那上个月呢\",\"sessionId\":\"nl-session-a\"}");

        ArgumentCaptor<Nl2sqlPlanRequest> captor =
                ArgumentCaptor.forClass(Nl2sqlPlanRequest.class);
        verify(nl2sqlClient, times(2)).plan(captor.capture(), anyString());
        List<Map<String, String>> history = captor.getAllValues().get(1).history();
        assertThat(history).hasSize(1);
        assertThat(history.get(0))
                .containsEntry("question", "最近30天各分类销售额")
                .containsEntry("rowCount", "1");
        assertThat(history.get(0).get("plan")).contains("gmv");

        Long ttl = redis.getExpire("nl2sql:session:" + admin.id() + ":nl-session-a");
        assertThat(ttl).isBetween(1L, 1800L);
    }

    @Test
    void failedPlanIsNotRemembered() throws Exception {
        Session admin = register(ADMIN_PHONE);
        when(nl2sqlClient.plan(any(), anyString())).thenReturn(new Nl2sqlPlanResponse(
                null, true, "词汇表没有这个维度", "deepseek", "nl2sql_plan_v1"));
        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"用户性别分布\",\"sessionId\":\"nl-session-b\"}"))
                .andExpect(status().isBadRequest());

        reset(nl2sqlClient);
        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of(), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));
        seedOrderData(admin.id());
        query(admin, "{\"question\":\"最近30天销售额\",\"sessionId\":\"nl-session-b\"}");

        ArgumentCaptor<Nl2sqlPlanRequest> captor =
                ArgumentCaptor.forClass(Nl2sqlPlanRequest.class);
        verify(nl2sqlClient).plan(captor.capture(), anyString());
        assertThat(captor.getValue().history()).isEmpty();
    }

    @Test
    void freeSqlIsDisabledByDefault() throws Exception {
        Session admin = register(ADMIN_PHONE);
        mockMvc.perform(post("/api/admin/nl2sql/free-query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT status FROM orders\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NL2SQL_FREE_SQL_DISABLED"));
    }

    @Test
    void invalidSessionIdIsRejected() throws Exception {
        Session admin = register(ADMIN_PHONE);
        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"销售额\",\"sessionId\":\"bad:key\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void auditMasksPiiInQuestion() throws Exception {
        Session admin = register(ADMIN_PHONE);
        seedOrderData(admin.id());
        stubPlan(new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of(), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10));

        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"最近30天销售额，手机号13800138000，订单号123456789012345"}
                                """))
                .andExpect(status().isOk());

        String question = jdbcTemplate.queryForObject(
                "SELECT question FROM nl2sql_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, admin.id());
        assertThat(question)
                .contains("138****8000")
                .contains("1234****2345")
                .doesNotContain("13800138000")
                .doesNotContain("123456789012345");
    }

    private void query(Session admin, String body) throws Exception {
        mockMvc.perform(post("/api/admin/nl2sql/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void stubPlan(Nl2sqlPlanResponse.Plan plan) {
        when(nl2sqlClient.plan(any(), anyString())).thenReturn(new Nl2sqlPlanResponse(
                plan, false, null, "deepseek", "nl2sql_plan_v1"));
    }

    private void seedOrderData(Long userId) {
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO product
                    (category_id, name, description, type, price, stock, status,
                     created_at, updated_at)
                VALUES (?, '分析商品', '测试', 'PHYSICAL', 19.90, 10, 'ON_SALE', NOW(3), NOW(3))
                """, categoryId);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = '分析商品'", Long.class);

        jdbcTemplate.update("""
                INSERT INTO orders
                    (order_no, user_id, status, total_amount, has_physical, paid_at, created_at)
                VALUES ('NL-ORDER-001', ?, 'PAID', 20.00, 1,
                        NOW(3) - INTERVAL 1 DAY, NOW(3) - INTERVAL 1 DAY)
                """, userId);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = 'NL-ORDER-001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_item
                    (order_id, product_id, product_name, product_type, price, quantity)
                VALUES (?, ?, '分析商品', 'PHYSICAL', 10.00, 2)
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
