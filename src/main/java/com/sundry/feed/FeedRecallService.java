package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 多路召回：偏好分类、协同、热销、新品，合并去重。
 * 只负责产出候选 ID；打分和多样性重排仍在 FeedService。
 */
@Service
public class FeedRecallService {

    private static final Logger log = LoggerFactory.getLogger(FeedRecallService.class);

    private static final int AFFINITY_DAYS = 14;
    private static final int TOP_CATEGORIES = 3;

    private final FeedQueryMapper feedQueryMapper;
    private final ProductMapper productMapper;
    private final FeedProperties properties;

    public FeedRecallService(FeedQueryMapper feedQueryMapper, ProductMapper productMapper,
                             FeedProperties properties) {
        this.feedQueryMapper = feedQueryMapper;
        this.productMapper = productMapper;
        this.properties = properties;
    }

    public RecallResult recall(Long userId) {
        Map<Long, Double> affinity = affinity(userId);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();

        int category = 0;
        int coPurchase = 0;
        if (userId != null) {
            List<Long> topCategories = affinity.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(TOP_CATEGORIES)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!topCategories.isEmpty()) {
                category = addAll(ids, feedQueryMapper.findProductIdsByCategories(
                        topCategories, limit(properties.getRecallCategoryLimit())));
            }
            coPurchase = addAll(ids, feedQueryMapper.findCoPurchaseProductIds(
                    userId, limit(properties.getRecallCoPurchaseLimit())));
        }

        int hot = addAll(ids, feedQueryMapper.findHotProductIds(
                LocalDateTime.now().minusDays(Math.max(1, properties.getRecallHotDays())),
                limit(properties.getRecallHotLimit())));
        int newCount = addAll(ids, feedQueryMapper.findNewProductIds(
                limit(properties.getRecallNewLimit())));

        int minCandidates = limit(properties.getRecallMinCandidates());
        if (ids.size() < minCandidates) {
            List<Long> fallback = productMapper
                    .findForFeed(limit(properties.getRecallMaxCandidates()))
                    .stream()
                    .map(Product::getId)
                    .toList();
            addAll(ids, fallback);
        }

        List<Long> result = ids.stream()
                .limit(limit(properties.getRecallMaxCandidates()))
                .toList();
        log.debug("feed recall userId={} total={} hot={} new={} category={} coPurchase={}",
                userId, result.size(), hot, newCount, category, coPurchase);
        return new RecallResult(result, hot, newCount, category, coPurchase, affinity);
    }

    private Map<Long, Double> affinity(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minusDays(AFFINITY_DAYS);
        Map<Long, Double> weights = new HashMap<>();
        for (CategoryWeight weight : feedQueryMapper.findViewCategoryWeights(userId, since)) {
            weights.merge(weight.getCategoryId(), weight.getWeight().doubleValue(), Double::sum);
        }
        for (CategoryWeight weight : feedQueryMapper.findPurchasedCategoryWeights(userId)) {
            weights.merge(weight.getCategoryId(), weight.getWeight().doubleValue() * 3, Double::sum);
        }
        return weights;
    }

    private int addAll(LinkedHashSet<Long> target, List<Long> source) {
        int before = target.size();
        target.addAll(source);
        return target.size() - before;
    }

    private int limit(int value) {
        return Math.max(1, value);
    }

    public record RecallResult(List<Long> ids, int hot, int newCount, int category,
                               int coPurchase, Map<Long, Double> affinity) {
    }
}
