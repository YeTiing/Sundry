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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        jdbcTemplate.update("DELETE FROM order_refund");
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
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].buyerPhone").value(USER_PHONE));
    }

    @Test
    void userOrderPageLoadsItemsForMultipleOrders() throws Exception {
        Session user = register(USER_PHONE);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE phone = ?", Long.class, USER_PHONE);

        long first = insertOrder(userId, "batch-order-1");
        long second = insertOrder(userId, "batch-order-2");
        insertOrderItem(first, "批量商品A");
        insertOrderItem(second, "批量商品B");

        // 批量组装：两单各自带明细，且买家信息正常
        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].items.length()").value(1))
                .andExpect(jsonPath("$.items[1].items.length()").value(1))
                .andExpect(jsonPath("$.items[0].buyerPhone").value(USER_PHONE));
    }

    private long insertOrder(Long userId, String orderNo) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, user_id, status, total_amount, has_physical, created_at)
                VALUES (?, ?, 'PAID', ?, 1, ?)
                """, orderNo, userId, new BigDecimal("10.00"), Timestamp.valueOf(LocalDateTime.now()));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
    }

    private void insertOrderItem(long orderId, String name) {
        jdbcTemplate.update("""
                INSERT INTO order_item (order_id, product_id, product_name, product_type, price, quantity)
                VALUES (?, 1, ?, 'PHYSICAL', ?, 1)
                """, orderId, name, new BigDecimal("10.00"));
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

    @Test
    void refundRequiresApprovalAndRestoresStock() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "退款实物", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-approve-key");
        pay(user.token(), orderNo);

        mockMvc.perform(post("/api/orders/{orderNo}/refund", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"不想要了"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_REQUESTED"))
                .andExpect(jsonPath("$.refundStatus").value("PENDING"))
                .andExpect(jsonPath("$.refundReason").value("不想要了"))
                .andExpect(jsonPath("$.refundAmount").value(20.00));
        // 审核期间不动库存
        assertThat(stockOf(productId)).isEqualTo(3);

        // 审核期间不能打包，也不能确认收货
        mockMvc.perform(post("/api/admin/orders/{orderNo}/pack", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
        mockMvc.perform(post("/api/orders/{orderNo}/confirm", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));

        mockMvc.perform(get("/api/admin/orders")
                        .param("status", "REFUND_REQUESTED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("APPROVED"))
                .andExpect(jsonPath("$.refundAmount").value(20.00))
                .andExpect(jsonPath("$.refundedAt").isNotEmpty());
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(reviewerOf(orderNo)).isEqualTo(userIdOf(ADMIN_PHONE));

        // 重复通过幂等，库存不重复回补
        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(refundCount(orderNo)).isEqualTo(1);
    }

    @Test
    void refundRejectionReturnsToPreviousStatus() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "拒绝退款", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-reject-key");
        pay(user.token(), orderNo);
        mockMvc.perform(post("/api/admin/orders/{orderNo}/pack", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());

        applyRefund(user.token(), orderNo, "买错了");

        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/reject", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"商品已打包，请收到后再申请"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PACKING"))
                .andExpect(jsonPath("$.refundStatus").value("REJECTED"))
                .andExpect(jsonPath("$.refundReviewRemark").value("商品已打包，请收到后再申请"));
        assertThat(stockOf(productId)).isEqualTo(3);

        // 拒绝后可以继续发货
        mockMvc.perform(post("/api/admin/orders/{orderNo}/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"SF999"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void rejectedRefundCanBeAppliedAgain() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "再次申请", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-again-key");
        pay(user.token(), orderNo);

        applyRefund(user.token(), orderNo, "第一次");
        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/reject", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"照片不清晰"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(post("/api/orders/{orderNo}/refund", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"第二次"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_REQUESTED"))
                .andExpect(jsonPath("$.refundReason").value("第二次"));

        // 被拒记录保留，新申请是新的一行
        assertThat(refundCount(orderNo)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_refund r JOIN orders o ON o.id = r.order_id
                WHERE o.order_no = ? AND r.status = 'REJECTED'
                """, Long.class, orderNo)).isEqualTo(1);
    }

    @Test
    void withdrawRefundReturnsToPreviousStatus() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "撤销退款", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-withdraw-key");
        pay(user.token(), orderNo);

        applyRefund(user.token(), orderNo, "先不退了");
        mockMvc.perform(post("/api/orders/{orderNo}/refund/withdraw", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.refundStatus").value("WITHDRAWN"));
        assertThat(stockOf(productId)).isEqualTo(3);

        // 重复撤销幂等
        mockMvc.perform(post("/api/orders/{orderNo}/refund/withdraw", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void refundRejectsPendingPaymentAndDigitalOrders() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);

        long physicalId = createPhysicalProduct(admin.token(), "待支付退款", 5);
        long cartItemId = addToCart(user.token(), physicalId, 1);
        long addressId = createAddress(user.token());
        String pendingOrderNo = createOrder(user.token(), cartItemId, addressId, "refund-pending-key");

        mockMvc.perform(post("/api/orders/{orderNo}/refund", pendingOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"未支付"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));

        long digitalId = createDigitalProduct(admin.token(), "数字不可退");
        long digitalCartItemId = addToCart(user.token(), digitalId, 1);
        String digitalOrderNo = createOrderWithoutAddress(user.token(), digitalCartItemId, "refund-digital-key");
        pay(user.token(), digitalOrderNo);

        mockMvc.perform(post("/api/orders/{orderNo}/refund", digitalOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"数字商品"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ALLOWED"));
    }

    @Test
    void shippedAndCompletedOrdersCannotRefund() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "已发货不可退", 10);
        long addressId = createAddress(user.token());

        long firstCart = addToCart(user.token(), productId, 1);
        String shippedOrderNo = createOrder(user.token(), firstCart, addressId, "refund-shipped-key");
        pay(user.token(), shippedOrderNo);
        packAndShip(admin.token(), shippedOrderNo, "SF100");

        mockMvc.perform(post("/api/orders/{orderNo}/refund", shippedOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"已发货"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));

        long secondCart = addToCart(user.token(), productId, 1);
        String completedOrderNo = createOrder(user.token(), secondCart, addressId, "refund-completed-key");
        pay(user.token(), completedOrderNo);
        packAndShip(admin.token(), completedOrderNo, "SF101");
        mockMvc.perform(post("/api/orders/{orderNo}/confirm", completedOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{orderNo}/refund", completedOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"已完成"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void refundEndpointsRespectOwnershipAndRoles() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        Session other = register(OTHER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "权限测试", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-permission-key");
        pay(user.token(), orderNo);

        // 不是自己的订单
        mockMvc.perform(post("/api/orders/{orderNo}/refund", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"别人的订单"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        // 原因为空
        mockMvc.perform(post("/api/orders/{orderNo}/refund", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 普通用户不能审核
        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        // 没有待审核申请时审核失败
        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/reject", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"无申请"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void refundedOrderCannotDownloadDigitalItem() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long physicalId = createPhysicalProduct(admin.token(), "混合实物", 5);
        long digitalId = createDigitalProduct(admin.token(), "混合数字");
        long physicalCart = addToCart(user.token(), physicalId, 1);
        long digitalCart = addToCart(user.token(), digitalId, 1);
        long addressId = createAddress(user.token());

        String orderNo = createOrder(user.token(), physicalCart, List.of(digitalCart), addressId,
                "refund-mixed-key");
        pay(user.token(), orderNo);

        MvcResult detail = mockMvc.perform(get("/api/orders/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        String body = detail.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Integer> ids = JsonPath.read(body, "$.items[*].id");
        List<String> types = JsonPath.read(body, "$.items[*].productType");
        long digitalItemId = ids.get(types.indexOf("DIGITAL"));

        // 退款前可以下载
        mockMvc.perform(get("/api/orders/{orderNo}/items/{itemId}/download", orderNo, digitalItemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());

        applyRefund(user.token(), orderNo, "整单退款");
        mockMvc.perform(post("/api/admin/orders/{orderNo}/refund/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(get("/api/orders/{orderNo}/items/{itemId}/download", orderNo, digitalItemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_REFUNDED"));
    }

    @Test
    void concurrentRefundApplicationCreatesSinglePendingRecord() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "并发申请", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-concurrent-apply");
        pay(user.token(), orderNo);
        Long userId = userIdOf(USER_PHONE);

        runConcurrently(() -> orderService.applyRefund(userId, orderNo, "并发申请"));

        assertThat(orderStatus(orderNo)).isEqualTo("REFUND_REQUESTED");
        assertThat(refundCount(orderNo)).isEqualTo(1);
    }

    @Test
    void concurrentRefundApprovalRestoresStockOnce() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "并发审核", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "refund-concurrent-approve");
        pay(user.token(), orderNo);
        applyRefund(user.token(), orderNo, "并发审核");
        Long adminId = userIdOf(ADMIN_PHONE);

        runConcurrently(() -> orderService.approveRefund(orderNo, adminId, null));

        assertThat(orderStatus(orderNo)).isEqualTo("REFUNDED");
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(refundCount(orderNo)).isEqualTo(1);
    }

    @Test
    void returnFlowRestoresStockAfterReceipt() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "退货商品", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-flow-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF200");

        applyReturn(user.token(), orderNo, "不合适");
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT kind FROM order_refund r JOIN orders o ON o.id = r.order_id
                WHERE o.order_no = ? ORDER BY r.id DESC LIMIT 1
                """, String.class, orderNo)).isEqualTo("RETURN");

        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNING"))
                .andExpect(jsonPath("$.refundStatus").value("RETURNING"));
        // 未寄回前不能确认收货
        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/receive", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));

        mockMvc.perform(post("/api/orders/{orderNo}/return/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"YT888"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURN_SHIPPED"))
                .andExpect(jsonPath("$.returnTrackingNo").value("YT888"));

        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/receive", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundKind").value("RETURN"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.refundAmount").value(20.00))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty());
        assertThat(stockOf(productId)).isEqualTo(5);

        // 重复确认收货幂等，库存不重复回补
        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/receive", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(refundCount(orderNo)).isEqualTo(1);
    }

    @Test
    void returnRejectedReturnsToShipped() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "拒绝退货", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-reject-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF201");

        applyReturn(user.token(), orderNo, "不想要了");
        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/reject", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"已拆封影响二次销售"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.refundKind").value("RETURN"))
                .andExpect(jsonPath("$.refundStatus").value("REJECTED"))
                .andExpect(jsonPath("$.refundReviewRemark").value("已拆封影响二次销售"));
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    @Test
    void returnWithdrawReturnsToShipped() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "撤销退货", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-withdraw-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF202");

        applyReturn(user.token(), orderNo, "先不退了");
        mockMvc.perform(post("/api/orders/{orderNo}/return/withdraw", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.refundStatus").value("WITHDRAWN"));
        assertThat(stockOf(productId)).isEqualTo(4);

        // 重复撤销幂等
        mockMvc.perform(post("/api/orders/{orderNo}/return/withdraw", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void completedOrderCanReturnWithinSevenDays() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "七天内退货", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-completed-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF203");
        mockMvc.perform(post("/api/orders/{orderNo}/confirm", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        applyReturn(user.token(), orderNo, "收到后不满意");
        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{orderNo}/return/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"YT889"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/receive", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    @Test
    void completedOrderOutsideSevenDaysCannotReturn() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "超期退货", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-expired-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF204");
        mockMvc.perform(post("/api/orders/{orderNo}/confirm", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE orders SET completed_at = ? WHERE order_no = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(8)), orderNo);

        mockMvc.perform(post("/api/orders/{orderNo}/return", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"超过七天了"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_WINDOW_EXPIRED"));
    }

    @Test
    void returnNotAllowedBeforeShipmentAndForDigital() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long physicalId = createPhysicalProduct(admin.token(), "未发货退货", 5);
        long cartItemId = addToCart(user.token(), physicalId, 1);
        long addressId = createAddress(user.token());
        String paidOrderNo = createOrder(user.token(), cartItemId, addressId, "return-paid-key");
        pay(user.token(), paidOrderNo);

        mockMvc.perform(post("/api/orders/{orderNo}/return", paidOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"未发货"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));

        long digitalId = createDigitalProduct(admin.token(), "数字不退货");
        long digitalCart = addToCart(user.token(), digitalId, 1);
        String digitalOrderNo = createOrderWithoutAddress(user.token(), digitalCart, "return-digital-key");
        pay(user.token(), digitalOrderNo);

        mockMvc.perform(post("/api/orders/{orderNo}/return", digitalOrderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"数字商品"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_ALLOWED"));
    }

    @Test
    void returnShipRequiresApproval() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "未同意寄回", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-ship-guard-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF205");

        applyReturn(user.token(), orderNo, "申请中");

        mockMvc.perform(post("/api/orders/{orderNo}/return/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"YT999"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void returnEndpointsRespectOwnershipAndRoles() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        Session other = register(OTHER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "退货权限", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-permission-key");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF206");

        mockMvc.perform(post("/api/orders/{orderNo}/return", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"别人的订单"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        mockMvc.perform(post("/api/orders/{orderNo}/return", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        applyReturn(user.token(), orderNo, "正常申请");

        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/receive", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void concurrentReturnApplicationCreatesSinglePendingRecord() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "并发退货申请", 5);
        long cartItemId = addToCart(user.token(), productId, 1);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-concurrent-apply");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF207");
        Long userId = userIdOf(USER_PHONE);

        runConcurrently(() -> orderService.applyReturn(userId, orderNo, "并发申请"));

        assertThat(orderStatus(orderNo)).isEqualTo("RETURN_REQUESTED");
        assertThat(refundCount(orderNo)).isEqualTo(1);
    }

    @Test
    void concurrentReturnReceiptRestoresStockOnce() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "并发确认退货", 5);
        long cartItemId = addToCart(user.token(), productId, 2);
        long addressId = createAddress(user.token());
        String orderNo = createOrder(user.token(), cartItemId, addressId, "return-concurrent-receive");
        pay(user.token(), orderNo);
        packAndShip(admin.token(), orderNo, "SF208");
        applyReturn(user.token(), orderNo, "并发确认");
        mockMvc.perform(post("/api/admin/orders/{orderNo}/return/approve", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{orderNo}/return/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"YT777"}
                                """))
                .andExpect(status().isOk());
        Long adminId = userIdOf(ADMIN_PHONE);

        runConcurrently(() -> orderService.completeReturn(orderNo, adminId));

        assertThat(orderStatus(orderNo)).isEqualTo("REFUNDED");
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(refundCount(orderNo)).isEqualTo(1);
    }

    private void applyReturn(String token, String orderNo, String reason) throws Exception {
        mockMvc.perform(post("/api/orders/{orderNo}/return", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s"}
                                """.formatted(reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURN_REQUESTED"));
    }

    private void runConcurrently(Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    task.run();
                    return null;
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void pay(String token, String orderNo) throws Exception {
        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private void packAndShip(String adminToken, String orderNo, String trackingNo) throws Exception {
        mockMvc.perform(post("/api/admin/orders/{orderNo}/pack", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/orders/{orderNo}/ship", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingNo":"%s"}
                                """.formatted(trackingNo)))
                .andExpect(status().isOk());
    }

    private void applyRefund(String token, String orderNo, String reason) throws Exception {
        mockMvc.perform(post("/api/orders/{orderNo}/refund", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s"}
                                """.formatted(reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_REQUESTED"));
    }

    private String createOrder(String token, long cartItemId, List<Long> extraCartItemIds,
                               long addressId, String key) throws Exception {
        List<Long> all = new ArrayList<>();
        all.add(cartItemId);
        all.addAll(extraCartItemIds);
        String ids = all.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%s],"addressId":%d}
                                """.formatted(ids, addressId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.orderNo");
    }

    private String orderStatus(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
    }

    private long refundCount(String orderNo) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_refund r JOIN orders o ON o.id = r.order_id
                WHERE o.order_no = ?
                """, Long.class, orderNo);
    }

    private Long reviewerOf(String orderNo) {
        return jdbcTemplate.queryForObject("""
                SELECT r.reviewer_id FROM order_refund r JOIN orders o ON o.id = r.order_id
                WHERE o.order_no = ? ORDER BY r.id DESC LIMIT 1
                """, Long.class, orderNo);
    }

    private Long userIdOf(String phone) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE phone = ?", Long.class, phone);
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
