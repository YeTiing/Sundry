package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductType;
import com.sundry.catalog.dto.ProductView;
import com.sundry.common.api.ScrollPage;
import com.sundry.common.exception.BusinessException;
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
import java.util.stream.Collectors;

/**
 * 首页推荐 Feed：单路候选 + 规则打分 + 多样性重排。
 * 结果按用户缓存成 Redis List，游标是列表下标，避免重复和漏项。
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private static final int CANDIDATE_LIMIT = 500;
    private static final int DIVERSITY_BLOCK = 12;
    private static final int CATEGORY_CAP_PER_BLOCK = 5;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);
    private static final String SNAPSHOT_KEY = "feed:home:%s";

    private final ProductMapper productMapper;
    private final FeedQueryMapper feedQueryMapper;
    private final ProductService productService;
    private final StringRedisTemplate redis;

    public FeedService(ProductMapper productMapper, FeedQueryMapper feedQueryMapper,
                       ProductService productService, StringRedisTemplate redis) {
        this.productMapper = productMapper;
        this.feedQueryMapper = feedQueryMapper;
        this.productService = productService;
        this.redis = redis;
    }

    public ScrollPage<ProductView> feed(Long userId, Long cursor, int size, boolean refresh) {
        int offset = cursor == null ? 0 : Math.max(0, cursor.intValue());
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

    private List<Long> rank(Long userId) {
        List<Product> products = productMapper.findForFeed(CANDIDATE_LIMIT);
        if (products.isEmpty()) {
            return List.of();
        }

        Set<Long> purchased = userId == null
                ? Set.of()
                : new HashSet<>(feedQueryMapper.findPurchasedProductIds(userId));
        Map<Long, Double> affinity = categoryAffinity(userId);
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

            double score = 0.50 * affinityScore
                    + 0.25 * hotness
                    + 0.15 * freshness
                    + 0.10 * random
                    + explore;
            scored.add(new Scored(product.getId(), product.getCategoryId(), score));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingLong(Scored::productId).reversed()));
        return diversify(scored);
    }

    private Map<Long, Double> categoryAffinity(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        Map<Long, Double> weights = new HashMap<>();
        for (CategoryWeight weight : feedQueryMapper.findViewCategoryWeights(userId, since)) {
            weights.merge(weight.getCategoryId(), weight.getWeight().doubleValue(), Double::sum);
        }
        // 购买比浏览权重更高
        for (CategoryWeight weight : feedQueryMapper.findPurchasedCategoryWeights(userId, since)) {
            weights.merge(weight.getCategoryId(), weight.getWeight().doubleValue() * 3, Double::sum);
        }
        return weights;
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
