package com.sundry.shopflow;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存字段、购物车、地址簿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartAddressIntegrationTest {

    private static final String SUPER_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
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
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM seckill_activity");
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
    void cartAddUpdateRemoveAndStockCheck() throws Exception {
        Session admin = register(SUPER_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "库存商品", 5, "ON_SALE");

        mockMvc.perform(post("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":2}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.available").value(true));

        // 再次加入同一商品，数量累加
        mockMvc.perform(post("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":2}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(4));

        // 超过库存
        mockMvc.perform(post("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":2}
                                """.formatted(productId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_ENOUGH"));

        MvcResult list = mockMvc.perform(get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stock").value(5))
                .andReturn();
        long itemId = ((Number) JsonPath.read(
                list.getResponse().getContentAsString(StandardCharsets.UTF_8), "$[0].id")).longValue();

        mockMvc.perform(put("/api/cart/{id}", itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3));

        mockMvc.perform(delete("/api/cart/{id}", itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void addressCrudAndDefault() throws Exception {
        Session user = register(USER_PHONE);

        MvcResult first = mockMvc.perform(post("/api/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receiverName":"张三","receiverPhone":"13800138000",
                                 "province":"浙江","city":"杭州","district":"西湖",
                                 "detail":"文三路 1 号","isDefault":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true))
                .andReturn();
        long firstId = ((Number) JsonPath.read(
                first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();

        MvcResult second = mockMvc.perform(post("/api/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receiverName":"李四","receiverPhone":"13900139000",
                                 "province":"浙江","city":"杭州","district":"滨江",
                                 "detail":"江南大道 2 号","isDefault":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false))
                .andReturn();
        long secondId = ((Number) JsonPath.read(
                second.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();

        mockMvc.perform(post("/api/addresses/{id}/default", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[0].isDefault").value(true));

        mockMvc.perform(put("/api/addresses/{id}", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receiverName":"李四改","receiverPhone":"13900139000",
                                 "province":"浙江","city":"杭州","district":"滨江",
                                 "detail":"江南大道 3 号"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiverName").value("李四改"));

        // 删除默认地址后，剩下那条自动成为默认
        mockMvc.perform(delete("/api/addresses/{id}", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstId))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    void productStockIsReturned() throws Exception {
        Session admin = register(SUPER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "库存回显", 7, "ON_SALE");

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(7));
    }

    private long createPhysicalProduct(String token, String name, int stock, String status) throws Exception {
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试商品","price":10.00,"stock":%d,
                                 "categoryId":%d,"type":"PHYSICAL","status":"%s"}
                                """.formatted(name, stock, categoryId, status)))
                .andExpect(status().isCreated())
                .andReturn();
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
        return new Session(JsonPath.read(body, "$.accessToken"),
                ((Number) JsonPath.read(body, "$.user.id")).longValue());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Session(String token, long id) {
    }
}
