package com.sundry.ai;

import com.jayway.jsonpath.JsonPath;
import com.sundry.ai.dto.RerankRequest;
import com.sundry.ai.dto.RerankResponse;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.catalog.ProductCacheService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 推流：异步入队、后台构建、ID 校验、失败降级和审计。
 */
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.internal-token=test-token",
        "app.ai.feed-candidate-limit=20",
        "app.ai.feed-rerank-limit=5",
        "app.ai.feed-batch-size=10",
        "app.ai.max-attempts=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiFeedIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138002";
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
    private AiFeedBuildProcessor processor;

    @Autowired
    private AiFeedStore store;

    @Autowired
    private ProductCacheService cache;

    @MockitoBean
    private AiFeedClient aiFeedClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_feed_log");
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
        clear(redis.keys("behavior:*"));
        cache.clearLocalCaches();
        smsSender.clear();
        reset(aiFeedClient);
    }

    @Test
    void refreshEnqueuesThenReadyWithReasons() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        List<List<Long>> captured = new ArrayList<>();
        stubRerankReversed(captured);

        MvcResult pending = refresh(user.token(), 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn();
        String buildId = JsonPath.read(body(pending), "$.aiBuildId");
        assertThat(store.queueSize()).isEqualTo(1);

        processor.buildPending();

        MvcResult ready = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", buildId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value(buildId))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].reason").value("AI推荐"))
                .andReturn();

        List<Long> expected = new ArrayList<>(captured.get(0));
        Collections.reverse(expected);
        assertThat(ids(ready).get(0)).isEqualTo(expected.get(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_feed_log WHERE user_id = ?", String.class, user.id()))
                .isEqualTo("SUCCESS");
    }

    @Test
    void outOfCandidateIdsAreIgnored() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(6);
        Long[] validId = new Long[1];
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    validId[0] = request.candidates().get(0).id();
                    return new RerankResponse(List.of(
                            new RerankResponse.RerankItem(999999L, "编造"),
                            new RerankResponse.RerankItem(validId[0], "有效")),
                            "deepseek-chat", "feed_rerank_v1");
                });

        String buildId = refreshAndBuild(user.token(), 5);

        MvcResult result = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", buildId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(result)).doesNotContain(999999L);
        assertThat(ids(result).get(0)).isEqualTo(validId[0]);
    }

    @Test
    void llmFailureFallsBackToRuleOrder() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(6);
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenThrow(new AiFeedException("AI_UNAVAILABLE"));

        refresh(user.token(), 5).andExpect(jsonPath("$.aiStatus").value("PENDING"));
        processor.buildPending();
        processor.buildPending();

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.items.length()").value(5));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_feed_log WHERE status = 'FALLBACK'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void anonymousNeverEnqueues() throws Exception {
        seedProducts(4);

        mockMvc.perform(get("/api/feed").param("size", "5").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("DISABLED"))
                .andExpect(jsonPath("$.aiBuildId").doesNotExist());

        assertThat(store.queueSize()).isZero();
    }

    @Test
    void duplicateRefreshKeepsOneQueueEntryAndUsesLatestBuild() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(6);
        stubRerankReversed(new ArrayList<>());

        String first = JsonPath.read(body(refresh(user.token(), 5).andReturn()), "$.aiBuildId");
        String second = JsonPath.read(body(refresh(user.token(), 5).andReturn()), "$.aiBuildId");
        assertThat(second).isNotEqualTo(first);
        assertThat(store.queueSize()).isEqualTo(1);
        assertThat(store.pending(user.id())).isEqualTo(second);

        processor.buildPending();

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", second)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value(second));
    }

    @Test
    void olderBuildDoesNotOverwriteNewerRequest() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(6);
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    // 构建期间用户又点了一次"换一批"
                    store.enqueue(user.id());
                    RerankRequest request = invocation.getArgument(0);
                    List<RerankResponse.RerankItem> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, request.candidates().size()); i++) {
                        items.add(new RerankResponse.RerankItem(
                                request.candidates().get(i).id(), "旧结果"));
                    }
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v1");
                });

        String first = JsonPath.read(body(refresh(user.token(), 5).andReturn()), "$.aiBuildId");
        processor.buildPending();

        String pending = store.pending(user.id());
        assertThat(pending).isNotNull().isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_feed_log WHERE status = 'STALE'", Long.class))
                .isEqualTo(1);
        assertThat(redis.hasKey("feed:ai:snapshot:" + user.id())).isFalse();

        // 新请求随后正常构建
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenReturn(new RerankResponse(List.of(), "deepseek-chat", "feed_rerank_v1"));
        processor.buildPending();

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", pending)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value(pending));
    }

    @Test
    void readySnapshotPaginatesWithoutDuplicates() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        stubRerankReversed(new ArrayList<>());
        String buildId = refreshAndBuild(user.token(), 5);

        MvcResult first = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", buildId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        long cursor = ((Number) JsonPath.read(body(first), "$.nextCursor")).longValue();

        MvcResult second = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("cursor", String.valueOf(cursor))
                        .param("buildId", buildId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();

        Set<Long> all = new HashSet<>(ids(first));
        all.addAll(ids(second));
        assertThat(all).hasSize(10);
    }

    private void stubRerankReversed(List<List<Long>> captured) {
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    List<Long> ids = new ArrayList<>();
                    for (RerankRequest.Candidate candidate : request.candidates()) {
                        ids.add(candidate.id());
                    }
                    captured.add(ids);

                    List<Long> reversed = new ArrayList<>(ids);
                    Collections.reverse(reversed);
                    List<RerankResponse.RerankItem> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, reversed.size()); i++) {
                        items.add(new RerankResponse.RerankItem(reversed.get(i), "AI推荐"));
                    }
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v1");
                });
    }

    private String refreshAndBuild(String token, int size) throws Exception {
        MvcResult pending = refresh(token, size).andReturn();
        String buildId = JsonPath.read(body(pending), "$.aiBuildId");
        processor.buildPending();
        return buildId;
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String token, int size)
            throws Exception {
        return mockMvc.perform(get("/api/feed")
                        .param("size", String.valueOf(size))
                        .param("refresh", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private void seedProducts(int count) {
        long software = categoryId("软件");
        long physical = categoryId("实物");
        for (int i = 1; i <= count; i++) {
            long category = i % 2 == 0 ? physical : software;
            jdbcTemplate.update("""
                    INSERT INTO product
                        (category_id, name, description, type, price, stock, status,
                         created_at, updated_at)
                    VALUES (?, ?, '测试', 'PHYSICAL', ?, 10, 'ON_SALE', NOW(3), NOW(3))
                    """, category, "AI测试商品" + i, 10 + i);
        }
    }

    private List<Long> ids(MvcResult result) throws Exception {
        List<Number> values = JsonPath.read(body(result), "$.items[*].id");
        List<Long> ids = new ArrayList<>();
        for (Number value : values) {
            ids.add(value.longValue());
        }
        return ids;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = ?", Long.class, name);
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
