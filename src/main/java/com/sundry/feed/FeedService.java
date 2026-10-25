package com.sundry.feed;

import com.sundry.ai.AiFeedStore;
import com.sundry.ai.AiProperties;
import com.sundry.ai.FeedSnapshot;
import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductType;
import com.sundry.catalog.dto.ProductView;
import com.sundry.common.api.ScrollPage;
import com.sundry.common.exception.BusinessException;
import com.sundry.feed.dto.FeedPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 首页推荐 Feed：单路候选 + 规则打分 + 多样性重排。
 * 登录用户在规则候选的基础上，由后台 worker 调 LLM 重排并写入 Redis 快照。
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FALLBACK = "FALLBACK";

    private static final int DIVERSITY_BLOCK = 12;
    private static final int CATEGORY_CAP_PER_BLOCK = 5;
    private static final int PROVISIONAL_HEAD_SHIFT = 12;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);
    private static final String SNAPSHOT_KEY = "feed:home:%s";

    private final ProductMapper productMapper;
    private final FeedQueryMapper feedQueryMapper;
    private final FeedRecallService feedRecallService;
    private final ProductService productService;
    private final StringRedisTemplate redis;
    private final AiProperties aiProperties;
    private final AiFeedStore aiFeedStore;

    public FeedService(ProductMapper productMapper, FeedQueryMapper feedQueryMapper,
                       FeedRecallService feedRecallService,
                       ProductService productService, StringRedisTemplate redis,
                       AiProperties aiProperties, AiFeedStore aiFeedStore) {
        this.productMapper = productMapper;
        this.feedQueryMapper = feedQueryMapper;
        this.feedRecallService = feedRecallService;
        this.productService = productService;
        this.redis = redis;
        this.aiProperties = aiProperties;
        this.aiFeedStore = aiFeedStore;
    }

    public FeedPage feed(Long userId, Long cursor, int size, boolean refresh, String buildId) {
        int offset = cursor == null ? 0 : Math.max(0, cursor.intValue());
        if (userId == null || !aiProperties.isEnabled()) {
            ScrollPage<ProductView> page = rulePage(userId, offset, size, refresh);
            return new FeedPage(page.items(), page.nextCursor(), page.hasMore(),
                    STATUS_DISABLED, null, null);
        }
        if (aiProperties.getInternalToken() == null || aiProperties.getInternalToken().isBlank()) {
            ScrollPage<ProductView> page = rulePage(userId, offset, size, refresh);
            return new FeedPage(page.items(), page.nextCursor(), page.hasMore(),
                    STATUS_FALLBACK, null, null);
        }
        return aiPage(userId, offset, size, refresh, buildId);
    }

    private FeedPage aiPage(Long userId, int offset, int size, boolean refresh, String buildId) {
        try {
            // 点击换一批，或无 buildId 的首屏请求（重新打开 / F5），都切下一份
            if (refresh || (buildId == null && offset == 0)) {
                FeedSnapshot variant = switchVariant(userId);
                boolean seeded = false;
                if (variant == null) {
                    variant = seedProvisional(userId);
                    seeded = variant != null;
                }
                if (variant != null) {
                    if (seeded) {
                        // 规则兜底变体先顶上，同时后台补算 AI
                        ensureBuild(userId, true);
                    } else {
                        finishSwitch(userId);
                    }
                    FeedPage page = pageFromSnapshot(variant, offset, size,
                            statusOf(userId, variant), variant.buildId());
                    recordExposed(userId, page.items());
                    return page;
                }
                return pendingPage(userId, offset, size, refresh);
            }

            FeedSnapshot variant = findVariant(userId, buildId);
            if (variant != null) {
                // 轮询到规则兜底变体时，AI 批次就绪则自动升级
                if (variant.ruleFallback() && aiFeedStore.hasPromotableNextBatch(userId)) {
                    aiFeedStore.promoteNext(userId);
                    FeedSnapshot upgraded = aiFeedStore.switchVariant(userId);
                    if (upgraded != null) {
                        variant = upgraded;
                        finishSwitch(userId);
                    }
                } else {
                    ensureBuild(userId, false);
                }
                return pageFromSnapshot(variant, offset, size,
                        statusOf(userId, variant), variant.buildId());
            }
            return pendingPage(userId, offset, size, false);
        } catch (RuntimeException ex) {
            log.warn("AI 变体池读取失败，降级规则排序 userId={}", userId, ex);
            ScrollPage<ProductView> page = rulePage(userId, offset, size, false);
            return new FeedPage(page.items(), page.nextCursor(), page.hasMore(),
                    STATUS_FALLBACK, null, null);
        }
    }

    /**
     * 点击换一批：待上架区有新鲜批次就先提升为正式池，再切一份。
     */
    private FeedSnapshot switchVariant(Long userId) {
        long next = aiFeedStore.usableNextPoolSize(userId);
        // 完整批次才提升；正式池为空时，即便只有一份也先用上，避免回退规则页
        if (next >= aiProperties.getEffectiveFeedVariantCount()
                || (next > 0 && aiFeedStore.usablePoolSize(userId) == 0)) {
            aiFeedStore.promoteNext(userId);
        }
        return aiFeedStore.switchVariant(userId);
    }

    private String statusOf(Long userId, FeedSnapshot variant) {
        if (!variant.ruleFallback()) {
            return STATUS_READY;
        }
        try {
            return aiFeedStore.pending(userId) != null ? STATUS_PENDING : STATUS_FALLBACK;
        } catch (RuntimeException ex) {
            return STATUS_FALLBACK;
        }
    }

    /**
     * 空池时先用规则分数生成 2 份临时变体，保证「换一批」立刻有内容可切。
     */
    private FeedSnapshot seedProvisional(Long userId) {
        if (hasUsableVariants(userId)) {
            return null;
        }
        String lock = aiFeedStore.trySeedLock(userId);
        if (lock == null) {
            // 另一个窗口正在播种；有货就直接用
            return aiFeedStore.switchVariant(userId);
        }
        try {
            if (hasUsableVariants(userId)) {
                return aiFeedStore.switchVariant(userId);
            }
            List<Long> ranked = rank(userId);
            if (ranked.isEmpty()) {
                return null;
            }
            List<FeedSnapshot.SnapshotItem> items = new ArrayList<>(ranked.size());
            for (Long id : ranked) {
                items.add(new FeedSnapshot.SnapshotItem(id, null));
            }
            int shift = Math.min(PROVISIONAL_HEAD_SHIFT, items.size());
            List<FeedSnapshot.SnapshotItem> shifted = new ArrayList<>(items.size());
            shifted.addAll(items.subList(shift, items.size()));
            shifted.addAll(items.subList(0, shift));

            LocalDateTime builtAt = LocalDateTime.now();
            FeedSnapshot pref = new FeedSnapshot("PREF:" + UUID.randomUUID(), builtAt,
                    FeedSnapshot.RULE_FALLBACK_MODEL, "rule_seed", items, "PREF");
            FeedSnapshot explore = new FeedSnapshot("EXPLORE:" + UUID.randomUUID(), builtAt,
                    FeedSnapshot.RULE_FALLBACK_MODEL, "rule_seed", shifted, "EXPLORE");
            aiFeedStore.pushVariants(userId, List.of(pref, explore));
            return aiFeedStore.switchVariant(userId);
        } finally {
            aiFeedStore.releaseSeedLock(userId, lock);
        }
    }

    private boolean hasUsableVariants(Long userId) {
        return aiFeedStore.usablePoolSize(userId) > 0
                || aiFeedStore.usableNextPoolSize(userId) > 0;
    }

    /**
     * 普通读取不推进指针；只有正式池为空时才把待上架区顶上。
     */
    private FeedSnapshot findVariant(Long userId, String buildId) {
        FeedSnapshot variant = aiFeedStore.findVariant(userId, buildId);
        if (variant != null) {
            return variant;
        }
        if (aiFeedStore.usablePoolSize(userId) == 0
                && aiFeedStore.usableNextPoolSize(userId) > 0) {
            aiFeedStore.promoteNext(userId);
            return aiFeedStore.findVariant(userId, buildId);
        }
        return null;
    }

    private void recordExposed(Long userId, List<ProductView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        try {
            aiFeedStore.recordSeen(userId, views.stream().map(ProductView::id).toList());
        } catch (RuntimeException ex) {
            log.warn("AI 曝光记录失败 userId={}", userId, ex);
        }
    }

    private FeedPage pendingPage(Long userId, int offset, int size, boolean forceBuild) {
        boolean building = ensureBuild(userId, forceBuild);
        ScrollPage<ProductView> page = rulePage(userId, offset, size, false);
        return new FeedPage(page.items(), page.nextCursor(), page.hasMore(),
                building ? STATUS_PENDING : STATUS_FALLBACK, null, null);
    }

    /**
     * 池子水位不足时入队补算。pending 存在说明已有构建在途；失败熔断期内不自动重试。
     */
    private boolean ensureBuild(Long userId, boolean force) {
        try {
            if (aiFeedStore.pending(userId) != null) {
                return true;
            }
            if (!force) {
                if (aiFeedStore.attempts(userId) >= aiProperties.getMaxAttempts()) {
                    return false;
                }
                long usable = aiFeedStore.usablePoolSize(userId)
                        + aiFeedStore.usableNextPoolSize(userId);
                if (usable >= aiProperties.getEffectiveFeedVariantCount()) {
                    return false;
                }
            }
            if (!aiFeedStore.tryBuildPermit(userId)) {
                return false;
            }
            return aiFeedStore.enqueue(userId) != null;
        } catch (RuntimeException ex) {
            log.warn("AI 补算入队失败 userId={}", userId, ex);
            return false;
        }
    }

    /**
     * 切满一轮后主动补算，让下一批提前备好。失败不影响本次返回。
     */
    private void finishSwitch(Long userId) {
        try {
            long round = aiFeedStore.bumpRound(userId);
            if (round >= aiProperties.getEffectiveFeedVariantCount()) {
                // 冷却/队列拒绝时保留轮次，下一次切换再试
                if (ensureBuild(userId, true)) {
                    aiFeedStore.resetRound(userId);
                }
                return;
            }
            ensureBuild(userId, false);
        } catch (RuntimeException ex) {
            log.warn("AI 补算水位检查失败 userId={}", userId, ex);
        }
    }

    private FeedPage pageFromSnapshot(FeedSnapshot snapshot, int offset, int size,
                                      String status, String buildId) {
        List<FeedSnapshot.SnapshotItem> items = snapshot.items() == null
                ? List.of()
                : snapshot.items();
        List<ProductView> views = new ArrayList<>();
        int index = Math.min(Math.max(0, offset), items.size());
        while (views.size() < size && index < items.size()) {
            FeedSnapshot.SnapshotItem item = items.get(index++);
            ProductView view = hydrate(item.id());
            if (view != null) {
                views.add(withReason(view, item.reason()));
            }
        }
        boolean hasMore = index < items.size();
        Long nextCursor = hasMore ? (long) index : null;
        return new FeedPage(views, nextCursor, hasMore, status, buildId, snapshot.buildId());
    }

    private ScrollPage<ProductView> rulePage(Long userId, int offset, int size, boolean refresh) {
        String key = SNAPSHOT_KEY.formatted(userId == null ? "anon" : userId);
        try {
            if (refresh) {
                redis.delete(key);
            }
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                List<Long> ranked = rank(userId);
                redis.delete(key);
                if (!ranked.isEmpty()) {
                    redis.opsForList().rightPushAll(key,
                            ranked.stream().map(String::valueOf).toList());
                    redis.expire(key, SNAPSHOT_TTL);
                }
            }
            Long total = redis.opsForList().size(key);
            long length = total == null ? 0 : total;
            return paginateFromRedis(key, offset, size, length);
        } catch (RuntimeException ex) {
            log.warn("Feed 缓存不可用，降级实时计算 userId={}", userId, ex);
            return paginate(rank(userId), offset, size);
        }
    }

    private ScrollPage<ProductView> paginateFromRedis(String key, int offset, int size, long total) {
        List<ProductView> items = new ArrayList<>();
        int cursor = offset;
        while (items.size() < size && cursor < total) {
            int want = Math.min(size - items.size(), 50);
            List<String> chunk = redis.opsForList().range(key, cursor, cursor + want - 1);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            cursor += chunk.size();
            for (String id : chunk) {
                ProductView view = hydrate(Long.parseLong(id));
                if (view != null) {
                    items.add(view);
                }
            }
        }
        boolean hasMore = cursor < total;
        Long nextCursor = hasMore ? (long) cursor : null;
        return new ScrollPage<>(items, nextCursor, hasMore);
    }

    private ScrollPage<ProductView> paginate(List<Long> ranked, int offset, int size) {
        List<ProductView> items = new ArrayList<>();
        int index = Math.min(offset, ranked.size());
        while (items.size() < size && index < ranked.size()) {
            ProductView view = hydrate(ranked.get(index));
            index++;
            if (view != null) {
                items.add(view);
            }
        }
        boolean hasMore = index < ranked.size();
        Long nextCursor = hasMore ? (long) index : null;
        return new ScrollPage<>(items, nextCursor, hasMore);
    }

    private ProductView hydrate(Long productId) {
        try {
            return productService.findPublic(productId);
        } catch (BusinessException ex) {
            return null;
        }
    }

    private ProductView withReason(ProductView view, String reason) {
        if (reason == null || reason.isBlank()) {
            return view;
        }
        return new ProductView(view.id(), view.name(), view.description(), view.type(),
                view.price(), view.stock(), view.categoryId(), view.categoryName(),
                view.coverFileId(), view.coverUrl(), view.digitalFileId(), view.status(),
                view.deletedAt(), view.createdAt(), reason, view.tags());
    }

    /**
     * 规则排序后的商品 ID。AI worker 用它在同一份候选上做重排。
     */
    public List<Long> rankIds(Long userId) {
        return rank(userId);
    }

    /**
     * 单个商品视图；不存在或已下架返回 null。
     */
    public ProductView viewOrNull(Long productId) {
        return hydrate(productId);
    }

    private List<Long> rank(Long userId) {
        FeedRecallService.RecallResult recall = feedRecallService.recall(userId);
        List<Long> recallIds = recall.ids();
        if (recallIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> byId = productMapper.findForFeedByIds(recallIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product, (a, b) -> a));
        List<Product> products = recallIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        if (products.isEmpty()) {
            return List.of();
        }

        Set<Long> purchased = userId == null
                ? Set.of()
                : new HashSet<>(feedQueryMapper.findPurchasedProductIds(userId));
        Map<Long, Double> affinity = recall.affinity();
        Map<String, Double> tagAffinity = recall.tagAffinity();
        double maxTagWeight = tagAffinity.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0);
        double maxAffinity = affinity.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0);
        Set<Long> topCategories = affinity.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        long maxSales = products.stream()
                .mapToLong(product -> product.getSalesCount() == null ? 0 : product.getSalesCount())
                .max().orElse(0);

        LocalDate today = LocalDate.now();
        List<Scored> scored = new ArrayList<>();
        for (Product product : products) {
            if (purchased.contains(product.getId())) {
                continue;
            }
            if (product.getType() == ProductType.PHYSICAL
                    && (product.getStock() == null || product.getStock() <= 0)) {
                continue;
            }

            double affinityScore = maxAffinity <= 0
                    ? 0
                    : affinity.getOrDefault(product.getCategoryId(), 0.0) / maxAffinity;
            double hotness = maxSales <= 0
                    ? 0
                    : (product.getSalesCount() == null ? 0 : product.getSalesCount()) / (double) maxSales;
            double freshness = freshness(product.getCreatedAt());
            double random = deterministicRandom(userId, product.getId(), today);
            double explore = maxAffinity > 0 && !topCategories.contains(product.getCategoryId())
                    ? 0.05
                    : 0;

            double tagScore = 0;
            if (maxTagWeight > 0 && product.getTags() != null && !product.getTags().isBlank()) {
                double tagSum = 0;
                for (String raw : product.getTags().split(",")) {
                    tagSum += tagAffinity.getOrDefault(raw.trim(), 0.0);
                }
                tagScore = Math.min(1.0, tagSum / maxTagWeight);
            }

            double score = 0.40 * affinityScore
                    + 0.20 * hotness
                    + 0.15 * freshness
                    + 0.10 * tagScore
                    + 0.05 * random
                    + explore;
            scored.add(new Scored(product.getId(), product.getCategoryId(), score));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingLong(Scored::productId).reversed()));
        return diversify(scored);
    }

    private double freshness(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0;
        }
        long days = Duration.between(createdAt, LocalDateTime.now()).toDays();
        if (days < 0) {
            days = 0;
        }
        return Math.max(0, 1 - days / 90.0);
    }

    private double deterministicRandom(Long userId, Long productId, LocalDate day) {
        int hash = Objects.hash(userId, productId, day);
        return Math.floorMod(hash, 1000) / 1000.0;
    }

    /**
     * 每 12 个为一块：同一分类最多 5 个，且最多连续 2 个。
     */
    private List<Long> diversify(List<Scored> sorted) {
        List<Scored> remaining = new ArrayList<>(sorted);
        List<Long> result = new ArrayList<>();
        Map<Long, Integer> categoryCounts = new HashMap<>();
        Long lastCategory = null;
        Long previousCategory = null;
        int inBlock = 0;

        while (!remaining.isEmpty()) {
            if (inBlock >= DIVERSITY_BLOCK) {
                inBlock = 0;
                categoryCounts.clear();
            }
            int pick = -1;
            for (int i = 0; i < remaining.size(); i++) {
                Scored candidate = remaining.get(i);
                if (categoryCounts.getOrDefault(candidate.categoryId(), 0)
                        >= CATEGORY_CAP_PER_BLOCK) {
                    continue;
                }
                if (candidate.categoryId().equals(lastCategory)
                        && candidate.categoryId().equals(previousCategory)) {
                    continue;
                }
                pick = i;
                break;
            }
            if (pick < 0) {
                pick = 0;
            }
            Scored chosen = remaining.remove(pick);
            result.add(chosen.productId());
            categoryCounts.merge(chosen.categoryId(), 1, Integer::sum);
            previousCategory = lastCategory;
            lastCategory = chosen.categoryId();
            inBlock++;
        }
        return result;
    }

    private record Scored(Long productId, Long categoryId, double score) {
    }
}
