package com.sundry.order;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单、扣库存、模拟支付、履约、取消、超时、数字下载。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderFlowIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String OTHER_PHONE = "13800138002";
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
    private OrderService orderService;

    @BeforeEach
    void setUp() {
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
    void physicalOrderFlowAndIdempotency() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "实物商品", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());

        String orderBody = """
                {"cartItemIds":[%d],"addressId":%d}
                """.formatted(cartItemId, addressId);

        MvcResult created = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .header("Idempotency-Key", "order-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.hasPhysical").value(true))
                .andExpect(jsonPath("$.totalAmount").value(20.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();
        String orderNo = JsonPath.read(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.orderNo");

        // 幂等：同 key 不会重复下单、不会重复扣库存
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .header("Idempotency-Key", "order-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value(orderNo));

        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_item", Long.class)).isZero();

        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        // 重复支付幂等
        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(post("/api/admin/orders/{orderNo}/pack", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PACKING"));

        mockMvc.perform(post("/api/admin/orders/{orderNo}/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"SF123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.trackingNo").value("SF123456"));

        mockMvc.perform(post("/api/orders/{orderNo}/confirm", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].orderNo").value(orderNo));

        mockMvc.perform(get("/api/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void cancelPendingOrderRestoresStock() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "取消商品", 3);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "cancel-key");

        assertThat(stockOf(productId)).isEqualTo(1);

        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stockOf(productId)).isEqualTo(3);

        // 取消后不能再支付
        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void insufficientStockRollsBackOrder() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "库存不足", 2);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());

        // 加购后商家把库存改成 0
        updateStock(admin.token(), productId, 0);

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .header("Idempotency-Key", "no-stock-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d}
                                """.formatted(cartItemId, addressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_ENOUGH"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_item", Long.class)).isEqualTo(1);
    }

    @Test
    void digitalOrderCanDownloadAfterPayment() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        Session other = register(OTHER_PHONE);
        long productId = createDigitalProduct(admin.token(), "数字商品");
        long cartItemId = addToCart(user.token(), productId, 1);

        String orderNo = createOrderWithoutAddress(user.token(), cartItemId, "digital-key");

        // 未支付不能下载
        MvcResult unpaid = mockMvc.perform(get("/api/orders/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        long itemId = ((Number) JsonPath.read(
                unpaid.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.items[0].id")).longValue();

        mockMvc.perform(get("/api/orders/{orderNo}/items/{itemId}/download", orderNo, itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAID"));

        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/api/orders/{orderNo}/items/{itemId}/download", orderNo, itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith(
                        "http://test-minio/download/")));

        // 其他用户看不到这个订单
        mockMvc.perform(get("/api/orders/{orderNo}/items/{itemId}/download", orderNo, itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        // 纯数字订单支付后可以直接确认完成
        mockMvc.perform(post("/api/orders/{orderNo}/confirm", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void timeoutCancelsPendingOrderAndRestoresStock() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "超时商品", 4);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "timeout-key");

        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE order_no = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(31)), orderNo);

        orderService.cancelTimeoutOrders();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
        assertThat(status).isEqualTo("CANCELLED");
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    private long createPhysicalProduct(String token, String name, int stock) throws Exception {
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":10.00,"stock":%d,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(name, stock, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private long createDigitalProduct(String token, String name) throws Exception {
        MvcResult init = mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"project.zip","contentType":"application/zip","sizeBytes":1024}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String initBody = init.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String uploadId = JsonPath.read(initBody, "$.uploadId");
        String key = JsonPath.read(initBody, "$.key");
        MvcResult completed = mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(uploadId, key)))
                .andExpect(status().isOk())
                .andReturn();
        long fileId = ((Number) JsonPath.read(
                completed.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);
        MvcResult product = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":10.00,
                                 "categoryId":%d,"type":"DIGITAL","digitalFileId":%d,"status":"ON_SALE"}
                                """.formatted(name, categoryId, fileId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                product.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private long addToCart(String token, long productId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":%d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private long createAddress(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receiverName":"张三","receiverPhone":"13800138000",
                                 "province":"浙江","city":"杭州","district":"西湖",
                                 "detail":"文三路 1 号","isDefault":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private String createOrder(String token, long cartItemId, long addressId, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d}
                                """.formatted(cartItemId, addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.orderNo");
    }

    private String createOrderWithoutAddress(String token, long cartItemId, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d]}
                                """.formatted(cartItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.orderNo");
    }

    private void updateStock(String token, long productId, int stock) throws Exception {
        mockMvc.perform(put("/api/admin/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"库存不足","description":"测试","price":10.00,"stock":%d,
                                 "categoryId":%d,"type":"PHYSICAL"}
                                """.formatted(stock, categoryId("实物"))))
                .andExpect(status().isOk());
    }

    private Long categoryId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private int stockOf(long productId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = ?", Integer.class, productId);
        return stock == null ? 0 : stock;
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
