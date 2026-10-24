package com.sundry.seckill;

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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀活动：创建、校验、启用、取消、时间窗口与公开读取。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeckillActivityIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String PASSWORD = "secret123";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

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
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM order_refund");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        Set<String> keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        smsSender.clear();
    }

    @Test
    void adminCanCreateActivateAndPubliclyRead() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createPhysicalProduct(admin.token(), "秒杀商品", "100.00", 10, "ON_SALE");

        MvcResult created = createActivity(admin.token(), productId, "50.00", 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.seckillPrice").value(50.00))
                .andExpect(jsonPath("$.remaining").value(5))
                .andExpect(jsonPath("$.perUserLimit").value(1))
                .andExpect(jsonPath("$.productName").value("秒杀商品"))
                .andReturn();
        long activityId = idOf(created);

        mockMvc.perform(get("/api/seckill/products/{productId}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SECKILL_NOT_FOUND"));

        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.activeNow").value(true));

        mockMvc.perform(get("/api/seckill/products/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activityId))
                .andExpect(jsonPath("$.seckillPrice").value(50.00))
                .andExpect(jsonPath("$.activeNow").value(true));
    }

    @Test
    void validationRejectsInvalidActivities() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createPhysicalProduct(admin.token(), "校验商品", "100.00", 10, "ON_SALE");
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);

        createActivity(admin.token(), productId, "100.01", 5, start, end)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECKILL_PRICE_INVALID"));

        createActivity(admin.token(), productId, "50.00", 11, start, end)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECKILL_STOCK_INVALID"));

        createActivity(admin.token(), productId, "50.00", 5, end, start)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECKILL_TIME_INVALID"));

        createActivity(admin.token(), productId, "50.00", 5,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECKILL_TIME_INVALID"));

        long draftProductId = createPhysicalProduct(admin.token(), "未上架", "100.00", 10, "DRAFT");
        createActivity(admin.token(), draftProductId, "50.00", 5, start, end)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECKILL_PRODUCT_NOT_SALEABLE"));
    }

    @Test
    void digitalProductUsesActivityQuota() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createDigitalProduct("数字秒杀品", "100.00");

        createActivity(admin.token(), productId, "9.90", 100,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productType").value("DIGITAL"))
                .andExpect(jsonPath("$.remaining").value(100));
    }

    @Test
    void overlappingActiveActivityRejected() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createPhysicalProduct(admin.token(), "重叠商品", "100.00", 10, "ON_SALE");

        long firstId = idOf(createActivity(admin.token(), productId, "50.00", 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))
                .andExpect(status().isCreated())
                .andReturn());
        long secondId = idOf(createActivity(admin.token(), productId, "40.00", 5,
                LocalDateTime.now(), LocalDateTime.now().plusHours(2))
                .andExpect(status().isCreated())
                .andReturn());

        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", firstId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECKILL_CONFLICT"));
    }

    @Test
    void onlyDraftCanBeEdited() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createPhysicalProduct(admin.token(), "编辑商品", "100.00", 10, "ON_SALE");
        long activityId = idOf(createActivity(admin.token(), productId, "50.00", 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))
                .andExpect(status().isCreated())
                .andReturn());

        mockMvc.perform(put("/api/admin/seckill/activities/{id}", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityBody(productId, "40.00", 3,
                                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seckillPrice").value(40.00))
                .andExpect(jsonPath("$.stock").value(3));

        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/seckill/activities/{id}", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityBody(productId, "30.00", 3,
                                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECKILL_STATUS_INVALID"));
    }

    @Test
    void cancelAndEndTransitions() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createPhysicalProduct(admin.token(), "状态商品", "100.00", 10, "ON_SALE");

        long draftId = idOf(createActivity(admin.token(), productId, "50.00", 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))
                .andReturn());
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/cancel", draftId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", draftId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECKILL_STATUS_INVALID"));

        long activeId = idOf(createActivity(admin.token(), productId, "45.00", 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", activeId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/end", activeId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/cancel", activeId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECKILL_STATUS_INVALID"));
    }

    @Test
    void adminListFiltersAndPaginates() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createPhysicalProduct(admin.token(), "列表商品", "100.00", 20, "ON_SALE");
        for (int i = 0; i < 3; i++) {
            createActivity(admin.token(), productId, "50.00", 2,
                    LocalDateTime.now().plusMinutes(i), LocalDateTime.now().plusHours(1 + i))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/admin/seckill/activities")
                        .param("status", "DRAFT")
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/admin/seckill/activities")
                        .param("status", "DRAFT")
                        .param("page", "2")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void normalUserCannotManageActivities() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "权限商品", "100.00", 10, "ON_SALE");

        mockMvc.perform(post("/api/admin/seckill/activities")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityBody(productId, "50.00", 5,
                                LocalDateTime.now(), LocalDateTime.now().plusHours(1))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/seckill/activities")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicOnlyReturnsActiveWithinWindow() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long futureProduct = createPhysicalProduct(admin.token(), "未开始", "100.00", 10, "ON_SALE");
        long futureId = idOf(createActivity(admin.token(), futureProduct, "50.00", 5,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", futureId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/seckill/products/{productId}", futureProduct))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SECKILL_NOT_FOUND"));

        long activeProduct = createPhysicalProduct(admin.token(), "进行中", "100.00", 10, "ON_SALE");
        long activeId = idOf(createActivity(admin.token(), activeProduct, "50.00", 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", activeId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/seckill/products/{productId}", activeProduct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId));
    }

    private org.springframework.test.web.servlet.ResultActions createActivity(
            String token, long productId, String price, int stock,
            LocalDateTime startAt, LocalDateTime endAt) throws Exception {
        return mockMvc.perform(post("/api/admin/seckill/activities")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(activityBody(productId, price, stock, startAt, endAt)));
    }

    private String activityBody(long productId, String price, int stock,
                                LocalDateTime startAt, LocalDateTime endAt) {
        return """
                {"productId":%d,"seckillPrice":%s,"stock":%d,"startAt":"%s","endAt":"%s"}
                """.formatted(productId, price, stock, startAt.format(ISO), endAt.format(ISO));
    }

    private long createPhysicalProduct(String token, String name, String price,
                                       int stock, String status) throws Exception {
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":%s,"stock":%d,
                                 "categoryId":%d,"type":"PHYSICAL","status":"%s"}
                                """.formatted(name, price, stock, categoryId, status)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private long createDigitalProduct(String name, String price) {
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO product (name, description, price, stock, category_id, type, status)
                VALUES (?, '测试', ?, 0, ?, 'DIGITAL', 'ON_SALE')
                """, name, new BigDecimal(price), categoryId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ? ORDER BY id DESC LIMIT 1", Long.class, name);
    }

    private long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
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

    private record Session(String token) {
    }
}
