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
import java.time.LocalDateTime;
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
        "app.ai.max-attempts=2",
        "app.ai.feed-variant-count=2",
        "app.ai.feed-variant-dedup-head=3",
        "app.ai.feed-variant-deadline=1s",
        "app.feed.recall-new-limit=200"
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

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

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

        refresh(user.token(), 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn();
        assertThat(store.queueSize()).isEqualTo(1);

        processor.buildPending();

        MvcResult ready = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].reason").value("AI推荐"))
                .andReturn();
        assertThat(JsonPath.<String>read(body(ready), "$.aiBuildId")).startsWith("PREF:");

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
        Double[] ruleScore = new Double[1];
        Integer[] ruleRank = new Integer[1];
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    RerankRequest.Candidate first = request.candidates().get(0);
                    validId[0] = first.id();
                    ruleScore[0] = first.ruleScore();
                    ruleRank[0] = first.ruleRank();
                    return new RerankResponse(List.of(
                            new RerankResponse.RerankItem(999999L, "编造"),
                            new RerankResponse.RerankItem(validId[0], "有效")),
                            "deepseek-chat", "feed_rerank_v3");
                });

        refreshAndBuild(user.token(), 5);

        MvcResult result = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(result)).doesNotContain(999999L);
        assertThat(ids(result).get(0)).isEqualTo(validId[0]);

        assertThat(ruleScore[0]).isNotNull();
        assertThat(ruleRank[0]).isEqualTo(1);
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT candidate_snapshot FROM ai_feed_log WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, user.id());
        assertThat(snapshot)
                .contains("\"candidates\"")
                .contains("\"ruleScore\"")
                .contains("\"variants\"")
                .contains("\"explored\"");
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

        refresh(user.token(), 5).andExpect(jsonPath("$.aiStatus").value("PENDING"));
        refresh(user.token(), 5).andExpect(jsonPath("$.aiStatus").value("PENDING"));
        assertThat(store.queueSize()).isEqualTo(1);
        String pending = store.pending(user.id());
        assertThat(pending).isNotNull();

        processor.buildPending();

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty());
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
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v2");
                });

        refresh(user.token(), 5).andExpect(jsonPath("$.aiStatus").value("PENDING"));
        processor.buildPending();

        String pending = store.pending(user.id());
        assertThat(pending).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_feed_log WHERE status = 'STALE'", Long.class))
                .isEqualTo(1);
        assertThat(store.nextPoolSize(user.id())).isZero();

        // 新请求随后正常构建
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenReturn(new RerankResponse(List.of(), "deepseek-chat", "feed_rerank_v2"));
        processor.buildPending();

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty());
    }

    @Test
    void readySnapshotPaginatesWithoutDuplicates() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        stubRerankReversed(new ArrayList<>());
        refreshAndBuild(user.token(), 5);

        MvcResult first = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andReturn();
        String buildId = JsonPath.read(body(first), "$.aiBuildId");
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

    @Test
    void buildWritesTwoStrategiesToStagingPool() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        Set<String> strategies = Collections.synchronizedSet(new HashSet<>());
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    strategies.add(request.strategy());
                    List<RerankResponse.RerankItem> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, request.candidates().size()); i++) {
                        items.add(new RerankResponse.RerankItem(
                                request.candidates().get(i).id(), "AI推荐"));
                    }
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v2");
                });

        refresh(user.token(), 5);
        processor.buildPending();

        assertThat(strategies).containsExactlyInAnyOrder("PREF", "EXPLORE");

        List<FeedSnapshot> variants = nextPool(user.id());
        assertThat(variants).hasSize(2);
        assertThat(variants.get(0).strategy()).isEqualTo("PREF");
        assertThat(variants.get(0).buildId()).matches("PREF:[0-9a-f-]{36}");
        assertThat(variants.get(1).buildId())
                .startsWith(variants.get(1).strategy() + ":");
    }

    @Test
    void variantHeadsAreDeduplicated() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    List<RerankResponse.RerankItem> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, request.candidates().size()); i++) {
                        items.add(new RerankResponse.RerankItem(
                                request.candidates().get(i).id(), "AI推荐"));
                    }
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v2");
                });

        refresh(user.token(), 5);
        processor.buildPending();

        List<FeedSnapshot> variants = nextPool(user.id());
        assertThat(variants).hasSize(2);
        List<Long> firstHead = headIds(variants.get(0), 3);
        List<Long> secondHead = headIds(variants.get(1), 3);
        assertThat(firstHead).doesNotContainAnyElementsOf(secondHead);
        assertThat(variants.get(1).items()).hasSize(variants.get(0).items().size());
    }

    @Test
    void partialVariantFailureUsesSuccessfulOne() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    if (!"PREF".equals(request.strategy())) {
                        throw new AiFeedException("AI_UNAVAILABLE");
                    }
                    List<RerankResponse.RerankItem> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, request.candidates().size()); i++) {
                        items.add(new RerankResponse.RerankItem(
                                request.candidates().get(i).id(), "AI推荐"));
                    }
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v2");
                });

        refresh(user.token(), 5);
        processor.buildPending();

        List<FeedSnapshot> variants = nextPool(user.id());
        assertThat(variants).hasSize(2);
        assertThat(variants).extracting(FeedSnapshot::strategy)
                .containsExactlyInAnyOrder("PREF", "EXPLORE");
        FeedSnapshot fallback = variants.stream()
                .filter(FeedSnapshot::ruleFallback)
                .findFirst()
                .orElseThrow();
        assertThat(fallback.strategy()).isEqualTo("EXPLORE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_feed_log WHERE status = 'SUCCESS'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void slowModelFallsBackToRuleOrderWithinDeadline() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AiFeedException("INTERRUPTED");
                    }
                    return new RerankResponse(List.of(), "deepseek-chat", "feed_rerank_v2");
                });

        long start = System.currentTimeMillis();
        refresh(user.token(), 5);
        processor.buildPending();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(6_000);
        List<FeedSnapshot> variants = nextPool(user.id());
        assertThat(variants).hasSize(2).allMatch(FeedSnapshot::ruleFallback);

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    private List<FeedSnapshot> nextPool(Long userId) throws Exception {
        List<String> values = redis.opsForList()
                .range("feed:ai:pool:next:" + userId, 0, -1);
        assertThat(values).isNotNull();
        List<FeedSnapshot> variants = new ArrayList<>();
        for (String value : values) {
            int first = value.indexOf('|');
            int second = first < 0 ? -1 : value.indexOf('|', first + 1);
            String json = second > first ? value.substring(second + 1) : value;
            variants.add(objectMapper.readValue(json, FeedSnapshot.class));
        }
        return variants;
    }

    private List<Long> headIds(FeedSnapshot snapshot, int count) {
        return snapshot.items().stream()
                .limit(count)
                .map(FeedSnapshot.SnapshotItem::id)
                .toList();
    }

    @Test
    void refreshSwitchesVariantsWithoutWaiting() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        when(aiFeedClient.rerank(any(RerankRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    RerankRequest request = invocation.getArgument(0);
                    List<RerankRequest.Candidate> candidates = request.candidates();
                    boolean reversed = "PREF".equals(request.strategy());
                    List<RerankResponse.RerankItem> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, candidates.size()); i++) {
                        int index = reversed ? candidates.size() - 1 - i : i;
                        items.add(new RerankResponse.RerankItem(
                                candidates.get(index).id(), "AI推荐"));
                    }
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v2");
                });

        refreshAndBuild(user.token(), 5);

        MvcResult first = refresh(user.token(), 5)
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andReturn();
        String firstVariant = JsonPath.read(body(first), "$.aiBuildId");

        MvcResult second = refresh(user.token(), 5)
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andReturn();
        String secondVariant = JsonPath.read(body(second), "$.aiBuildId");

        assertThat(secondVariant).isNotEqualTo(firstVariant);
        assertThat(ids(first)).isNotEqualTo(ids(second));
        // 刚构建过，冷却期内不重复构建
        assertThat(store.queueSize()).isZero();

        redis.delete("feed:ai:build:cooldown:" + user.id());
        MvcResult third = refresh(user.token(), 5)
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andReturn();
        assertThat(JsonPath.<String>read(body(third), "$.aiBuildId"))
                .isEqualTo(firstVariant);
        // 冷却解除后，切满一轮的补算才真正入队
        assertThat(store.queueSize()).isEqualTo(1);
    }

    @Test
    void paginationKeepsCurrentVariant() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM product ORDER BY id", Long.class);
        store.pushVariants(user.id(), List.of(
                variant("ACTIVE:A", ids.subList(0, 8).toArray(Long[]::new)),
                variant("ACTIVE:B", ids.subList(8, 12).toArray(Long[]::new))));

        MvcResult first = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:A"))
                .andReturn();
        long cursor = ((Number) JsonPath.read(body(first), "$.nextCursor")).longValue();

        // 浏览中途备好了新批次，翻页仍固定当前变体
        store.pushNextVariants(user.id(), List.of(
                variant("NEXT:C", ids.get(0), ids.get(1)),
                variant("NEXT:D", ids.get(2))));

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("cursor", String.valueOf(cursor))
                        .param("buildId", "ACTIVE:A")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:A"));

        // 重新打开（无 buildId 首屏）才切到新批次
        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("NEXT:C"));
    }

    @Test
    void firstVisitSeedsRuleVariantsAndSwitchesOnClick() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(24);

        MvcResult first = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn();
        String firstVariant = JsonPath.read(body(first), "$.aiBuildId");
        assertThat(firstVariant).startsWith("PREF:");
        assertThat(store.poolSize(user.id())).isEqualTo(2);
        assertThat(store.queueSize()).isEqualTo(1);

        MvcResult second = refresh(user.token(), 5)
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andReturn();
        String secondVariant = JsonPath.read(body(second), "$.aiBuildId");
        assertThat(secondVariant).startsWith("EXPLORE:");
        assertThat(ids(first)).isNotEqualTo(ids(second));

        // 轮询用当前 buildId，不推进指针
        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", secondVariant)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").value(secondVariant));
    }

    @Test
    void provisionalUpgradesToAiAfterBuild() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        stubRerankReversed(new ArrayList<>());

        MvcResult first = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andReturn();
        String provisional = JsonPath.read(body(first), "$.aiBuildId");
        assertThat(provisional).startsWith("PREF:");

        processor.buildPending();

        MvcResult upgraded = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .param("buildId", provisional)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andReturn();
        assertThat(JsonPath.<String>read(body(upgraded), "$.aiBuildId"))
                .isNotEqualTo(provisional);
        assertThat(store.nextPoolSize(user.id())).isZero();
    }

    @Test
    void variantLengthIsCapped() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(200);
        stubRerankReversed(new ArrayList<>());

        refreshAndBuild(user.token(), 5);

        assertThat(nextPool(user.id()))
                .hasSize(2)
                .allMatch(variant -> variant.items().size() == 120);
    }

    @Test
    void statusReportsPendingThenReady() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        stubRerankReversed(new ArrayList<>());

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("PENDING"));

        mockMvc.perform(get("/api/feed/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty());

        processor.buildPending();

        mockMvc.perform(get("/api/feed/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty())
                .andExpect(jsonPath("$.feedBuildId").isNotEmpty());
    }

    @Test
    void anonymousStatusIsDisabled() throws Exception {
        mockMvc.perform(get("/api/feed/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("DISABLED"));
    }

    @Test
    void offShelfProductsAreSkippedInBatchHydration() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(6);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM product ORDER BY id", Long.class);
        jdbcTemplate.update("UPDATE product SET status = 'OFF_SHELF' WHERE id = ?", ids.get(2));
        store.pushVariants(user.id(), List.of(
                variant("ACTIVE:A", ids.toArray(Long[]::new))));

        mockMvc.perform(get("/api/feed")
                        .param("size", "6")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void partialNextBatchIsNotPromotedWhileActivePoolIsHealthy() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM product ORDER BY id", Long.class);
        store.pushVariants(user.id(), List.of(
                variant("ACTIVE:A", ids.get(0), ids.get(1)),
                variant("ACTIVE:B", ids.get(2), ids.get(3))));
        store.pushNextVariants(user.id(), List.of(
                variant("NEXT:C", ids.get(4), ids.get(5))));

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:A"));

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:B"));
    }

    @Test
    void loneNextBatchIsUsedWhenActivePoolIsEmpty() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(6);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM product ORDER BY id", Long.class);
        store.pushNextVariants(user.id(), List.of(
                variant("NEXT:C", ids.get(0), ids.get(1))));

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("NEXT:C"));
    }

    @Test
    void reopenRotatesToNextVariant() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(12);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM product ORDER BY id", Long.class);
        store.pushVariants(user.id(), List.of(
                variant("ACTIVE:A", ids.get(0), ids.get(1)),
                variant("ACTIVE:B", ids.get(2), ids.get(3))));

        MvcResult first = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:A"))
                .andReturn();

        MvcResult second = mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:B"))
                .andReturn();
        assertThat(ids(first)).isNotEqualTo(ids(second));
        assertThat(store.queueSize()).isEqualTo(1);

        mockMvc.perform(get("/api/feed")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiBuildId").value("ACTIVE:A"));
    }

    @Test
    void emptyPoolRefreshEnqueuesOnlyOnce() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(4);

        refresh(user.token(), 3)
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty());
        refresh(user.token(), 3)
                .andExpect(jsonPath("$.aiStatus").value("PENDING"));

        assertThat(store.queueSize()).isEqualTo(1);
    }

    @Test
    void nonRefreshWithEmptyPoolTriggersBuild() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(4);

        mockMvc.perform(get("/api/feed")
                        .param("size", "3")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiStatus").value("PENDING"));

        assertThat(store.queueSize()).isEqualTo(1);
    }

    @Test
    void expiredVariantsFallBackToPending() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(4);
        Long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM product ORDER BY id LIMIT 1", Long.class);
        store.pushVariants(user.id(), List.of(
                new FeedSnapshot("OLD:A", LocalDateTime.now().minusMinutes(21),
                        "deepseek-chat", "feed_rerank_v2",
                        List.of(new FeedSnapshot.SnapshotItem(productId, null)), "PREF")));

        refresh(user.token(), 3)
                .andExpect(jsonPath("$.aiStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiBuildId").isNotEmpty());

        // 过期后先用规则兜底变体顶上，同时补算
        assertThat(store.poolSize(user.id())).isEqualTo(2);
        assertThat(store.queueSize()).isEqualTo(1);
    }

    @Test
    void repeatedVariantSinksRecentlyExposedHead() throws Exception {
        Session user = register(USER_PHONE);
        seedProducts(50);
        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT id FROM product ORDER BY id", Long.class);
        List<FeedSnapshot.SnapshotItem> itemsA = new ArrayList<>();
        List<FeedSnapshot.SnapshotItem> itemsB = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            itemsA.add(new FeedSnapshot.SnapshotItem(productIds.get(i), "AI推荐"));
            itemsB.add(new FeedSnapshot.SnapshotItem(productIds.get(25 + i), "AI推荐"));
        }
        store.pushVariants(user.id(), List.of(
                new FeedSnapshot("A", LocalDateTime.now(), "m", "v", itemsA, "PREF"),
                new FeedSnapshot("B", LocalDateTime.now(), "m", "v", itemsB, "EXPLORE")));

        MvcResult first = refresh(user.token(), 20)
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andReturn();
        List<Long> exposedHead = new ArrayList<>(ids(first).subList(0, 8));

        refresh(user.token(), 20)
                .andExpect(jsonPath("$.aiBuildId").value("B"));

        MvcResult again = refresh(user.token(), 20)
                .andExpect(jsonPath("$.aiStatus").value("READY"))
                .andExpect(jsonPath("$.aiBuildId").value("A"))
                .andReturn();
        List<Long> sunk = ids(again);

        assertThat(sunk.subList(0, 8)).doesNotContainAnyElementsOf(exposedHead);
        for (Long id : exposedHead) {
            assertThat(sunk.indexOf(id)).isBetween(11, 19);
        }

        // 翻页沿用下沉后的顺序，不重复
        MvcResult pageTwo = mockMvc.perform(get("/api/feed")
                        .param("size", "20")
                        .param("cursor", "20")
                        .param("buildId", "A")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiBuildId").value("A"))
                .andReturn();
        assertThat(ids(pageTwo)).doesNotContainAnyElementsOf(sunk);
    }

    private FeedSnapshot variant(String buildId, Long... ids) {
        List<FeedSnapshot.SnapshotItem> items = new ArrayList<>();
        for (Long id : ids) {
            items.add(new FeedSnapshot.SnapshotItem(id, "AI推荐"));
        }
        return new FeedSnapshot(buildId, LocalDateTime.now(), "deepseek-chat",
                "feed_rerank_v2", items, "PREF");
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
                    return new RerankResponse(items, "deepseek-chat", "feed_rerank_v2");
                });
    }

    private void refreshAndBuild(String token, int size) throws Exception {
        refresh(token, size).andExpect(jsonPath("$.aiStatus").value("PENDING"));
        processor.buildPending();
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
