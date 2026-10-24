package com.sundry.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.seckill.dto.CreateSeckillOrderRequest;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀抢购：Lua 预占、Kafka 消息（测试用内存发布器）、幂等建单与失败回补。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeckillOrderIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String USER2_PHONE = "13800138002";
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

    @Autowired
    private SeckillOrderService seckillOrderService;

    @Autowired
    private SeckillStockService stockService;

    @Autowired
    private SeckillOrderMessageConsumer consumer;

    @Autowired
    private RecordingSeckillOrderPublisher publisher;

    @Autowired
    private SeckillTimeoutService timeoutService;

    @Autowired
    private SeckillTimeoutProcessor timeoutProcessor;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SeckillReconcileService reconcileService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        clear(redis.keys("auth:*"));
        clear(redis.keys("seckill:*"));
        clear(redis.keys("rate:seckill:*"));
        publisher.clear();
        smsSender.clear();
    }

    @Test
    void seckillOrderCreatesOrderAndDeductsStock() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "秒杀实物", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        String requestId = submit(user.token(), activityId, addressId);
        assertThat(publisher.last()).isNotNull();
        assertThat(publisher.last().requestId()).isEqualTo(requestId);
        processLastMessage();

        mockMvc.perform(get("/api/seckill/results/{requestId}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderNo").isNotEmpty());

        assertThat(ordersOf(activityId)).isEqualTo(1);
        assertThat(soldCount(activityId)).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(4);
        assertThat(stockService.remaining(activityId)).isEqualTo(1);

        // 同一消息重复消费不会重复建单
        processLastMessage();
        assertThat(ordersOf(activityId)).isEqualTo(1);
    }

    @Test
    void repeatPurchaseReturnsSameOrder() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "重复抢购", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        String firstRequestId = submitAndProcess(user.token(), activityId, addressId);
        String firstOrderNo = orderNoOfResult(user.token(), firstRequestId);

        MvcResult second = mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, addressId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        String secondRequestId = requestIdOf(second);

        mockMvc.perform(get("/api/seckill/results/{requestId}", secondRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderNo").value(firstOrderNo));

        assertThat(ordersOf(activityId)).isEqualTo(1);
        assertThat(stockService.remaining(activityId)).isEqualTo(1);
    }

    @Test
    void soldOutDoesNotOversell() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        Session other = register(USER2_PHONE);
        long productId = createPhysicalProduct(admin.token(), "售罄商品", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 1);
        activate(admin.token(), activityId);
        long userAddress = createAddress(user.token());
        long otherAddress = createAddress(other.token());

        submitAndProcess(user.token(), activityId, userAddress);

        mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, otherAddress)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECKILL_SOLD_OUT"));

        assertThat(ordersOf(activityId)).isEqualTo(1);
        assertThat(soldCount(activityId)).isEqualTo(1);
        assertThat(stockService.remaining(activityId)).isZero();
    }

    @Test
    void concurrentBuyersDoNotOversell() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long productId = createDigitalProduct(admin.token(), "并发数字秒杀", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 3);
        activate(admin.token(), activityId);

        List<Session> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            users.add(register("1380013801" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Session user : users) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        seckillOrderService.submit(user.id(),
                                new CreateSeckillOrderRequest(activityId, null));
                        success.incrementAndGet();
                    } catch (RuntimeException ignored) {
                        // 售罄
                    }
                    return null;
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(3);
        assertThat(publisher.messages()).hasSize(3);
        for (SeckillOrderMessage message : publisher.messages()) {
            consumer.handle(objectMapper.writeValueAsString(message));
        }

        assertThat(ordersOf(activityId)).isEqualTo(3);
        assertThat(soldCount(activityId)).isEqualTo(3);
        assertThat(stockService.remaining(activityId)).isZero();
    }

    @Test
    void physicalSeckillRequiresAddress() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "需要地址", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);

        mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADDRESS_REQUIRED"));

        assertThat(stockService.remaining(activityId)).isEqualTo(2);
        assertThat(soldCount(activityId)).isZero();
    }

    @Test
    void dbStockFailureRollsBackRedisReservation() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "库存回滚", "100.00", 1);
        long activityId = createActivity(admin.token(), productId, "50.00", 1);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        jdbcTemplate.update("UPDATE product SET stock = 0 WHERE id = ?", productId);

        String requestId = submit(user.token(), activityId, addressId);
        processLastMessage();

        mockMvc.perform(get("/api/seckill/results/{requestId}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("STOCK_NOT_ENOUGH"));

        assertThat(ordersOf(activityId)).isZero();
        assertThat(soldCount(activityId)).isZero();
        assertThat(stockService.remaining(activityId)).isEqualTo(1);
        assertThat(redis.opsForHash().hasKey("seckill:buyer:" + activityId,
                String.valueOf(user.id()))).isFalse();
        double compensation = meterRegistry.get("sundry.seckill.compensation")
                .counter().count();
        assertThat(compensation).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void digitalSeckillNeedsNoAddressAndCanDownload() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "数字秒杀", "100.00");
        long activityId = createActivity(admin.token(), productId, "9.90", 5);
        activate(admin.token(), activityId);

        String requestId = submitAndProcess(user.token(), activityId, null);
        String orderNo = orderNoOfResult(user.token(), requestId);

        MvcResult detail = mockMvc.perform(get("/api/orders/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderSource").value("SECKILL"))
                .andReturn();
        long itemId = ((Number) JsonPath.read(
                detail.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.items[0].id")).longValue();

        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{orderNo}/items/{itemId}/download", orderNo, itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(
                        org.hamcrest.Matchers.startsWith("http://test-minio/download/")));
    }

    @Test
    void cancelRestoresActivityStockButKeepsBuyerBlocked() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "取消秒杀", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        String requestId = submitAndProcess(user.token(), activityId, addressId);
        String orderNo = orderNoOfResult(user.token(), requestId);

        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(soldCount(activityId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(stockService.remaining(activityId)).isEqualTo(2);

        MvcResult again = mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, addressId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        mockMvc.perform(get("/api/seckill/results/{requestId}", requestIdOf(again))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(orderNo));
        assertThat(ordersOf(activityId)).isEqualTo(1);
    }

    @Test
    void draftActivityCannotBeBought() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "草稿活动", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        long addressId = createAddress(user.token());

        mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, addressId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SECKILL_NOT_FOUND"));

        assertThat(ordersOf(activityId)).isZero();
    }

    @Test
    void redisLossIsRepairedLazily() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        Session other = register(USER2_PHONE);
        long productId = createDigitalProduct(admin.token(), "懒预热", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 5);
        activate(admin.token(), activityId);

        submitAndProcess(user.token(), activityId, null);
        stockService.clear(activityId);

        submitAndProcess(other.token(), activityId, null);

        assertThat(stockService.remaining(activityId)).isEqualTo(3);
        assertThat(ordersOf(activityId)).isEqualTo(2);
        assertThat(redis.opsForHash().hasKey("seckill:buyer:" + activityId,
                String.valueOf(user.id()))).isTrue();
    }

    @Test
    void publisherFailureRestoresReservation() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "投递失败", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 2);
        activate(admin.token(), activityId);

        publisher.failNextPublish();

        mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECKILL_NOT_READY"));

        assertThat(stockService.remaining(activityId)).isEqualTo(2);
        assertThat(redis.opsForHash().hasKey("seckill:buyer:" + activityId,
                String.valueOf(user.id()))).isFalse();
        assertThat(ordersOf(activityId)).isZero();
    }

    @Test
    void resultOnlyVisibleToOwner() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        Session other = register(USER2_PHONE);
        long productId = createDigitalProduct(admin.token(), "结果权限", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 2);
        activate(admin.token(), activityId);

        String requestId = submitAndProcess(user.token(), activityId, null);

        mockMvc.perform(get("/api/seckill/results/{requestId}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SECKILL_RESULT_NOT_FOUND"));
    }

    @Test
    void createdOrderIsScheduledForTimeout() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "超时队列", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 2);
        activate(admin.token(), activityId);

        String requestId = submitAndProcess(user.token(), activityId, null);
        String orderNo = orderNoOfResult(user.token(), requestId);

        Double score = redis.opsForZSet().score("seckill:timeout", orderNo);
        assertThat(score).isNotNull();
        assertThat(score).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void expiredOrderIsCancelledAndStockRestored() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "超时取消", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        String requestId = submitAndProcess(user.token(), activityId, addressId);
        String orderNo = orderNoOfResult(user.token(), requestId);

        timeoutService.add(orderNo, LocalDateTime.now().minusSeconds(1));
        timeoutProcessor.cancelExpired();

        assertThat(statusOf(orderNo)).isEqualTo("CANCELLED");
        assertThat(soldCount(activityId)).isZero();
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(stockService.remaining(activityId)).isEqualTo(2);
        // 购买标记保留，不能重复抢
        assertThat(redis.opsForHash().hasKey("seckill:buyer:" + activityId,
                String.valueOf(user.id()))).isTrue();
    }

    @Test
    void notYetExpiredOrderIsKept() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "未到期", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 2);
        activate(admin.token(), activityId);

        String requestId = submitAndProcess(user.token(), activityId, null);
        String orderNo = orderNoOfResult(user.token(), requestId);

        timeoutService.add(orderNo, LocalDateTime.now().plusMinutes(10));
        timeoutProcessor.cancelExpired();

        assertThat(statusOf(orderNo)).isEqualTo("PENDING_PAYMENT");
        assertThat(soldCount(activityId)).isEqualTo(1);
        assertThat(stockService.remaining(activityId)).isEqualTo(1);
    }

    @Test
    void paidOrderIsNotCancelledByTimeout() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "已支付不取消", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        String requestId = submitAndProcess(user.token(), activityId, addressId);
        String orderNo = orderNoOfResult(user.token(), requestId);
        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());

        timeoutService.add(orderNo, LocalDateTime.now().minusSeconds(1));
        timeoutProcessor.cancelExpired();

        assertThat(statusOf(orderNo)).isEqualTo("PAID");
        assertThat(soldCount(activityId)).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(4);
        assertThat(stockService.remaining(activityId)).isEqualTo(1);
    }

    @Test
    void timeoutCancellationIsIdempotent() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createPhysicalProduct(admin.token(), "重复取消", "100.00", 5);
        long activityId = createActivity(admin.token(), productId, "50.00", 2);
        activate(admin.token(), activityId);
        long addressId = createAddress(user.token());

        String requestId = submitAndProcess(user.token(), activityId, addressId);
        String orderNo = orderNoOfResult(user.token(), requestId);

        timeoutService.add(orderNo, LocalDateTime.now().minusSeconds(1));
        timeoutProcessor.cancelExpired();
        timeoutProcessor.cancelExpired();

        assertThat(statusOf(orderNo)).isEqualTo("CANCELLED");
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(stockService.remaining(activityId)).isEqualTo(2);
    }

    @Test
    void tooManySubmitsAreRateLimited() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "限流商品", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 5);

        // 测试 profile 限流窗口 60 秒、上限 2 次
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/seckill/orders")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(activityId, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SECKILL_NOT_FOUND"));
        }

        mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, null)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        double limited = meterRegistry.get("sundry.seckill.submit")
                .tag("result", "RATE_LIMITED").counter().count();
        assertThat(limited).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void metricsRecordSuccessfulOrderAndCompensation() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "指标商品", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 2);
        activate(admin.token(), activityId);

        submitAndProcess(user.token(), activityId, null);

        double accepted = meterRegistry.get("sundry.seckill.submit")
                .tag("result", "accepted").counter().count();
        double success = meterRegistry.get("sundry.seckill.order")
                .tag("result", "success").counter().count();
        assertThat(accepted).isGreaterThanOrEqualTo(1.0);
        assertThat(success).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void prometheusEndpointExposesSeckillMetrics() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "指标端点", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 2);

        // 触发一次指标注册
        mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, null)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sundry_seckill_submit")));
    }

    @Test
    void reconcileReportsAndRepairsRedisDrift() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "对账商品", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 5);
        activate(admin.token(), activityId);
        submitAndProcess(user.token(), activityId, null);

        var consistent = reconcileService.reconcile(activityId, false);
        assertThat(consistent.stockConsistent()).isTrue();
        assertThat(consistent.soldConsistent()).isTrue();
        assertThat(consistent.repaired()).isFalse();
        assertThat(consistent.redisRemaining()).isEqualTo(4);
        assertThat(consistent.activeOrders()).isEqualTo(1);

        // 人为制造 Redis 库存漂移
        redis.opsForValue().set("seckill:stock:" + activityId, "99");
        var drifted = reconcileService.reconcile(activityId, false);
        assertThat(drifted.stockConsistent()).isFalse();
        assertThat(drifted.soldConsistent()).isTrue();
        assertThat(drifted.redisRemaining()).isEqualTo(99);
        assertThat(drifted.repaired()).isFalse();

        var repaired = reconcileService.reconcile(activityId, true);
        assertThat(repaired.repaired()).isTrue();
        assertThat(repaired.redisRemaining()).isEqualTo(4);
        assertThat(repaired.stockConsistent()).isTrue();
    }

    @Test
    void reconcileEndpointRequiresAdmin() throws Exception {
        Session admin = register(ADMIN_PHONE);
        Session user = register(USER_PHONE);
        long productId = createDigitalProduct(admin.token(), "对账权限", "100.00");
        long activityId = createActivity(admin.token(), productId, "10.00", 5);
        activate(admin.token(), activityId);

        mockMvc.perform(post("/api/admin/seckill/activities/{id}/reconcile", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/seckill/activities/{id}/reconcile", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(activityId))
                .andExpect(jsonPath("$.stockConsistent").value(true))
                .andExpect(jsonPath("$.soldConsistent").value(true));
    }

    private String submit(String token, long activityId, Long addressId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/seckill/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(activityId, addressId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andReturn();
        return requestIdOf(result);
    }

    private String submitAndProcess(String token, long activityId, Long addressId) throws Exception {
        String requestId = submit(token, activityId, addressId);
        processLastMessage();
        return requestId;
    }

    private void processLastMessage() throws Exception {
        SeckillOrderMessage message = publisher.last();
        assertThat(message).isNotNull();
        orderServiceProcess(message);
    }

    private void orderServiceProcess(SeckillOrderMessage message) throws Exception {
        consumer.handle(objectMapper.writeValueAsString(message));
    }

    private String orderNoOfResult(String token, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/seckill/results/{requestId}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.orderNo");
    }

    private String requestIdOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.requestId");
    }

    private long createActivity(String token, long productId, String price, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/seckill/activities")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"seckillPrice":%s,"stock":%d,
                                 "startAt":"%s","endAt":"%s"}
                                """.formatted(productId, price, stock,
                                LocalDateTime.now().minusMinutes(1).format(ISO),
                                LocalDateTime.now().plusHours(1).format(ISO))))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private void activate(String token, long activityId) throws Exception {
        mockMvc.perform(post("/api/admin/seckill/activities/{id}/activate", activityId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeNow").value(true));
    }

    private String orderBody(long activityId, Long addressId) {
        if (addressId == null) {
            return "{\"activityId\":%d}".formatted(activityId);
        }
        return "{\"activityId\":%d,\"addressId\":%d}".formatted(activityId, addressId);
    }

    private long createPhysicalProduct(String token, String name, String price, int stock)
            throws Exception {
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":%s,"stock":%d,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(name, price, stock, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private long createDigitalProduct(String token, String name, String price) throws Exception {
        MvcResult init = mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"seckill.zip","contentType":"application/zip","sizeBytes":1024}
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
                                {"name":"%s","description":"测试","price":%s,
                                 "categoryId":%d,"type":"DIGITAL","digitalFileId":%d,
                                 "status":"ON_SALE"}
                                """.formatted(name, price, categoryId, fileId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                product.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
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

    private int stockOf(long productId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = ?", Integer.class, productId);
        return stock == null ? 0 : stock;
    }

    private int soldCount(long activityId) {
        Integer sold = jdbcTemplate.queryForObject(
                "SELECT sold_count FROM seckill_activity WHERE id = ?", Integer.class, activityId);
        return sold == null ? 0 : sold;
    }

    private long ordersOf(long activityId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE activity_id = ?", Long.class, activityId);
    }

    private String statusOf(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
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
        Number id = JsonPath.read(body, "$.user.id");
        return new Session(JsonPath.read(body, "$.accessToken"), id.longValue());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private record Session(String token, Long id) {
    }
}
